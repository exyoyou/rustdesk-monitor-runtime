package youyou.monitor.runtime

import org.json.JSONArray
import org.json.JSONObject
import youyou.monitor.config.model.LocationTrackConfig
import youyou.monitor.logger.Log
import youyou.monitor.sync.storage.StorageRepository
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal class LocationTrackManager(
    private val storageRepository: StorageRepository
) {
    companion object {
        private const val TAG = "LocationTrackManager"
        private const val STAY_RADIUS_METERS = 200.0
        private const val STAY_MIN_DURATION_MS = 10 * 60 * 1000L
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }

    private val zoneId: ZoneId = ZoneId.systemDefault()
    @Volatile
    private var lastSavedPoint: TrackPoint? = null

    @Volatile
    private var rawRetentionDays: Long = 2L
    @Volatile
    private var rawDedupMinDistanceMeters: Double = 8.0
    @Volatile
    private var rawDedupMinIntervalMs: Long = 15_000L

    data class TrackPoint(
        val timestampMs: Long,
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val poiName: String?
    )

    fun updateConfig(config: LocationTrackConfig) {
        rawRetentionDays = config.rawRetentionDays.coerceAtLeast(1).toLong()
        rawDedupMinDistanceMeters = config.rawDedupMinDistanceMeters.coerceAtLeast(0.0)
        rawDedupMinIntervalMs = config.rawDedupMinIntervalMs.coerceAtLeast(0L)
    }

    fun recordPoint(
        latitude: Double,
        longitude: Double,
        timestampMs: Long = System.currentTimeMillis(),
        accuracyMeters: Float? = null,
        poiName: String? = null
    ) {
        try {
            val current = TrackPoint(
                timestampMs = timestampMs,
                latitude = latitude,
                longitude = longitude,
                accuracyMeters = accuracyMeters,
                poiName = poiName
            )
            if (shouldSkipRawPoint(current)) {
                return
            }

            val file = getRawFile(timestampMs)
            file.parentFile?.mkdirs()

            val obj = JSONObject().apply {
                put("timestampMs", timestampMs)
                put("latitude", latitude)
                put("longitude", longitude)
                if (accuracyMeters != null) put("accuracyMeters", accuracyMeters)
                if (!poiName.isNullOrBlank()) put("poiName", poiName)
            }

            file.appendText(obj.toString() + "\n")
            lastSavedPoint = current
        } catch (e: Exception) {
            Log.w(TAG, "记录轨迹点失败: ${e.message}")
        }
    }

    fun generateDailyReport(day: String? = null): File? {
        val reportDay = day ?: LocalDate.now(zoneId).minusDays(1).format(DAY_FMT)
        val rawFile = getRawFile(reportDay)
        if (!rawFile.exists()) return null

        return try {
            val points = rawFile.readLines()
                .asSequence()
                .mapNotNull { parsePoint(it) }
                .sortedBy { it.timestampMs }
                .toList()

            if (points.isEmpty()) return null

            val stays = analyzeStay(points)
            val totalDistance = calculateDistanceMeters(points)

            val reportObj = JSONObject().apply {
                put("day", reportDay)
                put("generatedAt", Instant.now().toString())
                put("totalPoints", points.size)
                put("totalDistanceMeters", totalDistance)

                val stayArray = JSONArray()
                stays.forEach { stay ->
                    stayArray.put(
                        JSONObject().apply {
                            put("startTime", Instant.ofEpochMilli(stay.startTs).toString())
                            put("endTime", Instant.ofEpochMilli(stay.endTs).toString())
                            put("durationMinutes", stay.durationMs / 60000.0)
                            put("latitude", stay.latitude)
                            put("longitude", stay.longitude)
                            put("poiName", stay.poiName ?: "")
                        }
                    )
                }
                put("stays", stayArray)
            }

            val reportFile = getReportFile(reportDay)
            reportFile.parentFile?.mkdirs()
            reportFile.writeText(reportObj.toString(2))
            reportFile
        } catch (e: Exception) {
            Log.e(TAG, "生成日报失败: ${e.message}", e)
            null
        }
    }

    fun getPendingReportFiles(): List<File> {
        try {
            generateDailyReport(LocalDate.now(zoneId).minusDays(1).format(DAY_FMT))
            generateDailyReport(LocalDate.now(zoneId).format(DAY_FMT))
        } catch (_: Exception) {
        }
        cleanupOldRawFiles()

        val dir = getReportDir()
        if (!dir.exists()) return emptyList()

        return dir.listFiles { file -> file.isFile && file.name.endsWith(".json") }
            ?.sortedBy { it.name }
            ?: emptyList()
    }

    fun onReportUploaded(reportFile: File) {
        try {
            if (reportFile.exists() && !reportFile.delete()) {
                Log.w(TAG, "已上传但删除报告失败: ${reportFile.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "删除已上传报告失败: ${e.message}")
        }
    }

    private data class StaySegment(
        val startTs: Long,
        val endTs: Long,
        val durationMs: Long,
        val latitude: Double,
        val longitude: Double,
        val poiName: String?
    )

    private fun analyzeStay(points: List<TrackPoint>): List<StaySegment> {
        if (points.size < 2) return emptyList()
        val stays = mutableListOf<StaySegment>()

        var i = 0
        while (i < points.size - 1) {
            var j = i + 1
            var latSum = points[i].latitude
            var lngSum = points[i].longitude
            var count = 1

            while (j < points.size) {
                val centerLat = latSum / count
                val centerLng = lngSum / count
                val distance = haversineMeters(
                    centerLat,
                    centerLng,
                    points[j].latitude,
                    points[j].longitude
                )

                if (distance > STAY_RADIUS_METERS) break

                latSum += points[j].latitude
                lngSum += points[j].longitude
                count++
                j++
            }

            val start = points[i]
            val end = points[j - 1]
            val duration = end.timestampMs - start.timestampMs

            if (duration >= STAY_MIN_DURATION_MS) {
                val poiName = points.subList(i, j)
                    .mapNotNull { it.poiName?.takeIf { p -> p.isNotBlank() } }
                    .firstOrNull()

                stays.add(
                    StaySegment(
                        startTs = start.timestampMs,
                        endTs = end.timestampMs,
                        durationMs = duration,
                        latitude = latSum / count,
                        longitude = lngSum / count,
                        poiName = poiName
                    )
                )
            }

            i = if (j == i + 1) i + 1 else j
        }

        return stays
    }

    private fun calculateDistanceMeters(points: List<TrackPoint>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (i in 1 until points.size) {
            total += haversineMeters(
                points[i - 1].latitude,
                points[i - 1].longitude,
                points[i].latitude,
                points[i].longitude
            )
        }
        return total
    }

    private fun parsePoint(line: String): TrackPoint? {
        return try {
            val obj = JSONObject(line)
            TrackPoint(
                timestampMs = obj.optLong("timestampMs", 0L),
                latitude = obj.optDouble("latitude", 0.0),
                longitude = obj.optDouble("longitude", 0.0),
                accuracyMeters = if (obj.has("accuracyMeters")) obj.optDouble("accuracyMeters").toFloat() else null,
                poiName = obj.optString("poiName", "").ifBlank { null }
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun getRawFile(timestampMs: Long): File {
        val day = Instant.ofEpochMilli(timestampMs).atZone(zoneId).toLocalDate().format(DAY_FMT)
        return getRawFile(day)
    }

    private fun getRawFile(day: String): File {
        return File(getRawDir(), "$day.jsonl")
    }

    private fun getReportFile(day: String): File {
        return File(getReportDir(), "$day-report.json")
    }

    private fun getRawDir(): File {
        return File(storageRepository.getRootDir(), "Location/raw")
    }

    private fun getReportDir(): File {
        return File(storageRepository.getRootDir(), "Location/report")
    }

    private fun shouldSkipRawPoint(current: TrackPoint): Boolean {
        val prev = lastSavedPoint ?: return false
        val dt = current.timestampMs - prev.timestampMs
        if (dt < 0) return false

        if (dt == 0L &&
            current.latitude == prev.latitude &&
            current.longitude == prev.longitude
        ) {
            return true
        }

        val distance = haversineMeters(
            prev.latitude,
            prev.longitude,
            current.latitude,
            current.longitude
        )

        return dt < rawDedupMinIntervalMs && distance < rawDedupMinDistanceMeters
    }

    private fun cleanupOldRawFiles() {
        try {
            val rawDir = getRawDir()
            if (!rawDir.exists() || !rawDir.isDirectory) return

            val expireBefore = LocalDate.now(zoneId).minusDays(rawRetentionDays)
            val files = rawDir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return

            files.forEach { file ->
                val day = file.name.removeSuffix(".jsonl")
                val date = runCatching { LocalDate.parse(day, DAY_FMT) }.getOrNull() ?: return@forEach
                if (date.isBefore(expireBefore)) {
                    if (!file.delete()) {
                        Log.w(TAG, "清理旧轨迹原始文件失败: ${file.name}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理旧轨迹原始文件异常: ${e.message}")
        }
    }

    private fun haversineMeters(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double
    ): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }
}
