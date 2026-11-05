package youyou.monitor.runtime

import android.content.Context
import android.hardware.HardwareBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import org.koin.core.context.GlobalContext
import youyou.monitor.amap.AMapTrackOptions
import youyou.monitor.amap.AmapTrackCollector
import youyou.monitor.config.model.LocationTrackConfig
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.logger.Log
import youyou.monitor.screen.MonitorService
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.infra.repository.TemplateRepositoryImpl
import youyou.monitor.sync.config.ConfigRepositoryImpl
import youyou.monitor.sync.storage.StorageRepository
import youyou.monitor.sync.task.ScheduledTaskManager
import java.io.File

object MonitorRuntime {
    private const val TAG = "MonitorRuntime"

    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var started = false

    // 常驻配置同步：没有录屏时也要定时检查远程配置，才能在开关打开时去开启录屏
    private var configSyncResident = false
    private var recordEnabledJob: Job? = null

    private var appContext: Context? = null
    private var monitorService: MonitorService? = null
    private var syncCoordinator: MonitorSyncCoordinator? = null
    private var locationTrackManager: LocationTrackManager? = null
    private var amapTrackCollector: AmapTrackCollector? = null
    private var amapTrackOptions: AMapTrackOptions = AMapTrackOptions()
    private var isAmapTrackStarted: Boolean = false
    private var locationConfigScope: CoroutineScope? = null
    private var locationConfigJob: Job? = null

    private var pendingDeviceIdProvider: (() -> String)? = null
    private var pendingRootDirChanged: ((String) -> Unit)? = null

    fun init(context: Context, deviceIdProvider: (() -> String)? = null) {
        if (BuildConfig.DEBUG) {
            startFdMonitor()
        }
        synchronized(lock) {
            if (deviceIdProvider != null) {
                pendingDeviceIdProvider = deviceIdProvider
            }
            if (initialized) {
                configureDeviceIdProviderLocked(pendingDeviceIdProvider)
                return
            }

            val applicationContext = context.applicationContext
            appContext = applicationContext

            MonitorService.init(applicationContext)
            monitorService = MonitorService.getInstance()

            val koin = GlobalContext.get()
            val configRepository = koin.get<ConfigRepository>()
            val configRepositoryImpl = koin.get<ConfigRepositoryImpl>()
            val templateRepository = koin.get<TemplateRepository>()
            val templateRepositoryImpl = koin.get<TemplateRepositoryImpl>()
            val storageRepository = koin.get<StorageRepository>()
            val scheduledTaskManager = koin.get<ScheduledTaskManager>()

            syncCoordinator = MonitorSyncCoordinator(
                applicationContext,
                configRepository,
                configRepositoryImpl,
                templateRepository,
                templateRepositoryImpl,
                storageRepository,
                scheduledTaskManager
            )

            locationTrackManager = LocationTrackManager(storageRepository)
            amapTrackCollector = AmapTrackCollector(applicationContext)

            val cfgScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            locationConfigScope = cfgScope
            locationConfigJob = cfgScope.launch {
                configRepository.getConfigFlow()
                    .map { it.locationTrack }
                    .distinctUntilChanged()
                    .collectLatest { cfg ->
                        applyLocationTrackConfig(cfg)
                    }
            }

            scheduledTaskManager.setTrackReportProvider {
                locationTrackManager?.getPendingReportFiles() ?: emptyList()
            }
            scheduledTaskManager.setOnTrackReportUploaded { file ->
                locationTrackManager?.onReportUploaded(file)
            }

            configureDeviceIdProviderLocked(pendingDeviceIdProvider)
            syncCoordinator?.setOnRootDirChanged(pendingRootDirChanged)

            initialized = true
            Log.i(TAG, "MonitorRuntime initialized")
        }
    }

    fun start() {
        synchronized(lock) {
            ensureInitializedLocked()
            if (started) return

            monitorService?.start()
            syncCoordinator?.start()
            started = true
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!initialized || !started) return

            try {
                if (!configSyncResident) {
                    syncCoordinator?.stop()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stop sync coordinator failed: ${e.message}")
            }

            try {
                monitorService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Stop monitor service failed: ${e.message}")
            }

            try {
                amapTrackCollector?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Stop amap collector failed: ${e.message}")
            }

            isAmapTrackStarted = false

            started = false
        }
    }

    /**
     * 只启动配置/文件同步并监听总开关（recordEnabled），不处理画面；录屏之外也能收到远程配置变化。
     * 回调在后台线程触发，注册后会立刻回调一次当前值。
     */
    fun startConfigSync(onRecordEnabledChanged: (Boolean) -> Unit) {
        synchronized(lock) {
            ensureInitializedLocked()
            configSyncResident = true
            syncCoordinator?.start()
            recordEnabledJob?.cancel()
            val configRepository = GlobalContext.get().get<ConfigRepository>()
            recordEnabledJob = locationConfigScope?.launch {
                configRepository.getConfigFlow()
                    .map { it.recordEnabled }
                    .distinctUntilChanged()
                    .collect { onRecordEnabledChanged(it) }
            }
        }
    }

    fun stopConfigSync() {
        synchronized(lock) {
            configSyncResident = false
            recordEnabledJob?.cancel()
            recordEnabledJob = null
            // 正在录屏时同步仍由 start()/stop() 管理，这里不动
            if (initialized && !started) {
                syncCoordinator?.stop()
            }
        }
    }

    fun setDeviceIdProvider(provider: (() -> String)?) {
        synchronized(lock) {
            pendingDeviceIdProvider = provider
            if (!initialized) return
            configureDeviceIdProviderLocked(provider)
        }
    }

    fun setOnRootDirChanged(callback: ((String) -> Unit)?) {
        synchronized(lock) {
            pendingRootDirChanged = callback
            syncCoordinator?.setOnRootDirChanged(callback)
        }
    }

    fun getRootDirPath(): String {
        synchronized(lock) {
            ensureInitializedLocked()
        }
        return GlobalContext.get().get<StorageRepository>().getRootDirPath()
    }

    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int, scale: Int = 1, rowStride: Int = width * 4) {
        monitorService?.onFrameAvailable(buffer, width, height, scale, rowStride)
    }

    fun recordTrackPoint(
        latitude: Double,
        longitude: Double,
        timestampMs: Long = System.currentTimeMillis(),
        accuracyMeters: Float? = null,
        poiName: String? = null
    ) {
        locationTrackManager?.recordPoint(
            latitude = latitude,
            longitude = longitude,
            timestampMs = timestampMs,
            accuracyMeters = accuracyMeters,
            poiName = poiName
        )
    }

    fun generateDailyTrackReport(day: String? = null): String? {
        return locationTrackManager?.generateDailyReport(day)?.absolutePath
    }

    private fun startAmapTrackInternal(options: AMapTrackOptions) {
        synchronized(lock) {
            ensureInitializedLocked()
            amapTrackOptions = options
            isAmapTrackStarted = true
        }
        amapTrackCollector?.start(amapTrackOptions) { point ->
            recordTrackPoint(
                latitude = point.latitude,
                longitude = point.longitude,
                timestampMs = point.timestampMs,
                accuracyMeters = point.accuracyMeters,
                poiName = point.poiName
            )
        }
    }

    fun startAmapTrack(
        intervalMs: Long = 60_000L,
        needAddress: Boolean = true,
        onceLocation: Boolean = true,
        singleShotPolling: Boolean = true,
        idleOnly: Boolean = true
    ) {
        startAmapTrackInternal(
            AMapTrackOptions(
                intervalMs = intervalMs,
                needAddress = needAddress,
                onceLocation = onceLocation || singleShotPolling,
                singleShotPolling = singleShotPolling,
                idleOnly = idleOnly
            )
        )
    }

    fun stopAmapTrack() {
        synchronized(lock) {
            isAmapTrackStarted = false
        }
        amapTrackCollector?.stop()
    }

    private fun applyLocationTrackConfig(config: LocationTrackConfig) {
        val options = AMapTrackOptions(
            intervalMs = config.staticIntervalMs,
            needAddress = config.needAddress,
            onceLocation = config.singleShotPolling,
            singleShotPolling = config.singleShotPolling,
            idleOnly = config.idleOnly,
            adaptiveInterval = config.adaptiveInterval,
            movingIntervalMs = config.movingIntervalMs,
            staticIntervalMs = config.staticIntervalMs,
            movementThresholdMeters = config.movementThresholdMeters,
            maxAcceptAccuracyMeters = config.maxAcceptAccuracyMeters
        )

        val changeLog = synchronized(lock) {
            locationTrackManager?.updateConfig(config)
            val oldOptions = amapTrackOptions
            val changed = options != amapTrackOptions
            amapTrackOptions = options
            Triple(changed && isAmapTrackStarted, oldOptions, isAmapTrackStarted)
        }
        val shouldRestart = changeLog.first
        val oldOptions = changeLog.second
        val wasStarted = changeLog.third

        if (oldOptions != options) {
            Log.i(
                TAG,
                "LocationTrack config applied: old=$oldOptions, new=$options, collectorStarted=$wasStarted"
            )
        }

        if (shouldRestart) {
            try {
                amapTrackCollector?.stop()
            } catch (_: Exception) {
            }
            startAmapTrackInternal(options)
            Log.i(TAG, "Location track config changed, collector restarted")
        } else if (wasStarted) {
            Log.i(TAG, "Location track config unchanged, collector keeps running")
        }
    }

    fun writeHardwareBufferToBuffer(
        hardwareBuffer: HardwareBuffer,
        dest: ByteBuffer
    ): Int {
        return RuntimeNativeLib.nativeWriteHardwareBufferToBuffer(hardwareBuffer, dest)
    }

    fun getApplicationContext(): Context {
        synchronized(lock) {
            ensureInitializedLocked()
            return appContext!!
        }
    }

    private fun configureDeviceIdProviderLocked(provider: (() -> String)?) {
        try {
            GlobalContext.get().get<ConfigRepositoryImpl>().setDeviceIdProvider(provider)
            Log.d(TAG, "DeviceIdProvider configured")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure deviceIdProvider: ${e.message}", e)
        }
    }

    private fun ensureInitializedLocked() {
        check(initialized) { "MonitorRuntime not initialized, call init(context) first." }
    }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun startFdMonitor() {
        applicationScope.launch {
            while (isActive) {
                try {
                    val fdFile = File("/proc/self/fd")
                    val fds = fdFile.listFiles()
                    val fdCount = fds?.size ?: 0

                    Log.d(
                        "DEBUG_FD",
                        "Current PID: ${android.os.Process.myPid()} | Global FD count: $fdCount"
                    )

                    // 如果 FD 数量异常，打印前 5 个 FD 具体是什么情况
                    if (fdCount > 800) {
                        Log.e("DEBUG_FD", "WARNING: High FD usage!")
                        fds?.take(5)?.forEach {
                            try {
                                val path = android.system.Os.readlink(it.absolutePath)
                                Log.e("DEBUG_FD", "FD ${it.name} -> $path")
                            } catch (e: Exception) {
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("DEBUG_FD", "Monitor error: ${e.message}")
                }

                // 每隔 1000 毫秒执行一次
                delay(1000)
            }
        }
    }
}
