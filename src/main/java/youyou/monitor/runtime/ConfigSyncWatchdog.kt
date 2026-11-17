package youyou.monitor.runtime

import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import youyou.monitor.logger.Log
import youyou.monitor.sync.config.ConfigRepositoryImpl

/**
 * 配置拉取看门狗：配置拉取是最重要的链路，不依赖定时任务"到点去拉"，而是持续检查它是否健康，不健康就立刻恢复。
 *  - 每 [TICK_MS] 检查一次：距上次成功超过刷新间隔就拉（配置的 configSyncIntervalMinutes，未配置用 [defaultRefreshIntervalMs]）；失败按 1、2、4… 分钟退避重试，最长 [MAX_RETRY_MS]；
 *  - 拉取不依赖 WebDAV 客户端是否已配置（直接按本地配置里的服务器测速下载），成功后会通过回调配置好客户端；
 *  - 单次拉取有整体超时 [SYNC_TIMEOUT_MS]，阻塞的网络调用不会卡住看门狗；
 *  - 循环吞掉一切异常，不会自己退出；外部（WorkManager）通过 [ensureRunning] 检查心跳，循环不在或心跳过期就重建。
 * 上次成功时间取自 [ConfigRepositoryImpl.lastSyncSuccessAt]，启动时的加载拉取成功也会计入。
 * 时间判断都用单调时钟：新鲜度和重试用 elapsedRealtime（含休眠，改系统时间不影响）；
 * 心跳用 uptimeMillis（休眠时不走），设备深度休眠导致 delay 变长不会被误判为循环卡死。
 */
internal class ConfigSyncWatchdog(
    private val configRepository: ConfigRepositoryImpl,
    private val defaultRefreshIntervalMs: Long
) {
    companion object {
        private const val TAG = "ConfigSyncWatchdog"
        private const val TICK_MS = 60_000L
        // 进程启动时常驻层自己会先拉一次，首轮稍等，避免重复拉取
        private const val STARTUP_DELAY_MS = 30_000L
        private const val FIRST_RETRY_MS = 60_000L
        private const val MAX_RETRY_MS = 15 * 60_000L
        // 配置的间隔上限：配错成很大的值时，设备也至少每天拉一次，还能用新配置改回来
        private const val MAX_REFRESH_MINUTES = 24 * 60
        private const val SYNC_TIMEOUT_MS = 5 * 60_000L
        // 一轮最长 = 拉取超时 + 一个 tick，超过两轮没有心跳就认为循环卡死
        private const val STALE_HEARTBEAT_MS = 2 * (SYNC_TIMEOUT_MS + TICK_MS)
    }

    private val lock = Any()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var loopJob: Job? = null

    @Volatile
    private var heartbeatAt = 0L

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var nextRetryAt = 0L

    /** 启动看门狗；已经在运行且心跳正常时不做任何事，否则重建循环。返回是否做了重建 */
    fun ensureRunning(): Boolean {
        synchronized(lock) {
            val job = loopJob
            val now = SystemClock.uptimeMillis()
            if (job != null && job.isActive && now - heartbeatAt < STALE_HEARTBEAT_MS) {
                return false
            }
            if (job != null) {
                Log.w(TAG, "看门狗循环异常（active=${job.isActive}, 心跳 ${(now - heartbeatAt) / 1000}s 前），重建")
                job.cancel()
            }
            heartbeatAt = now
            loopJob = scope.launch { loop() }
            return true
        }
    }

    /** 立即检查一次：数据过期（或从未成功）就马上拉，返回当前是否健康 */
    suspend fun checkNow(): Boolean {
        if (isFresh(SystemClock.elapsedRealtime())) return true
        return syncOnce()
    }

    private suspend fun loop() {
        Log.i(TAG, "看门狗已启动，当前刷新间隔 ${refreshIntervalMs() / 1000}s")
        delay(STARTUP_DELAY_MS)
        while (scope.isActive) {
            heartbeatAt = SystemClock.uptimeMillis()
            try {
                val now = SystemClock.elapsedRealtime()
                if (!isFresh(now) && now >= nextRetryAt) {
                    syncOnce()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Log.e(TAG, "看门狗检查出错: ${t.message}")
            }
            delay(TICK_MS)
        }
    }

    private suspend fun isFresh(now: Long): Boolean {
        val last = configRepository.lastSyncSuccessAt
        return last > 0L && now - last < refreshIntervalMs()
    }

    private suspend fun refreshIntervalMs(): Long {
        val minutes = configRepository.getCurrentConfig().configSyncIntervalMinutes
        if (minutes <= 0) return defaultRefreshIntervalMs
        return minutes.coerceAtMost(MAX_REFRESH_MINUTES) * 60_000L
    }

    private suspend fun syncOnce(): Boolean {
        val attempt = scope.async { configRepository.syncFromRemote() }
        val result = withTimeoutOrNull(SYNC_TIMEOUT_MS) { attempt.await() }
        if (result == null) {
            attempt.cancel()
        }
        val now = SystemClock.elapsedRealtime()
        if (result != null && result.isSuccess) {
            if (consecutiveFailures > 0) {
                Log.i(TAG, "配置拉取已恢复（之前连续失败 $consecutiveFailures 次）")
            }
            consecutiveFailures = 0
            nextRetryAt = 0L
            return true
        }

        consecutiveFailures++
        val backoff = (FIRST_RETRY_MS shl minOf(consecutiveFailures - 1, 10)).coerceAtMost(MAX_RETRY_MS)
        nextRetryAt = now + backoff
        val reason = if (result == null) "超时" else result.exceptionOrNull()?.message
        Log.w(TAG, "配置拉取失败（连续 $consecutiveFailures 次）: $reason，${backoff / 1000}s 后重试")
        return false
    }
}
