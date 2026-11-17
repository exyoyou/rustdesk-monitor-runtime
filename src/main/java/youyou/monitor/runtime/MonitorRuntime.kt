package youyou.monitor.runtime

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/**
 * 监控模块入口，分两条互不影响的生命周期：
 *  - 常驻层 [startBackground]：配置下载/更新、文件上传、模板下载、存储清理、定位（是否定位由配置决定）。
 *    进程在就一直运行，不停止，不依赖 OpenCV；
 *  - 识别层 [startScreen] / [stopScreen]：OpenCV 屏幕识别，和录屏绑定，录屏开才开。
 * 两者都会先调用 [init]（日志、Koin、仓储等基础对象，可重复调用，失败后下次调用会重试）。
 */
object MonitorRuntime {
    private const val TAG = "MonitorRuntime"

    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var backgroundStarted = false

    @Volatile
    private var screenStarted = false

    private var appContext: Context? = null
    private var monitorService: MonitorService? = null
    private var syncCoordinator: MonitorSyncCoordinator? = null
    private var locationTrackManager: LocationTrackManager? = null
    private var amapTrackCollector: AmapTrackCollector? = null
    private var amapTrackOptions: AMapTrackOptions = AMapTrackOptions()
    private var isAmapTrackStarted: Boolean = false
    private var backgroundScope: CoroutineScope? = null
    private var configSyncWatchdog: ConfigSyncWatchdog? = null

    private var pendingDeviceIdProvider: (() -> String)? = null
    private var pendingRootDirChanged: ((String) -> Unit)? = null

    fun init(context: Context, deviceIdProvider: (() -> String)? = null) {
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

            // 只初始化日志和 Koin，不加载 OpenCV（OpenCV 在 startScreen 时才加载）
            youyou.monitor.screen.di.initKoin(applicationContext)

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
            backgroundScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

            scheduledTaskManager.setTrackReportProvider {
                locationTrackManager?.getPendingReportFiles() ?: emptyList()
            }
            scheduledTaskManager.setOnTrackReportUploaded { file ->
                locationTrackManager?.onReportUploaded(file)
            }

            configureDeviceIdProviderLocked(pendingDeviceIdProvider)
            syncCoordinator?.setOnRootDirChanged(pendingRootDirChanged)

            configSyncWatchdog = ConfigSyncWatchdog(
                configRepositoryImpl,
                defaultRefreshIntervalMs = if (BuildConfig.DEBUG) 60_000L else 15 * 60_000L
            )

            initialized = true
            // 配置拉取不随录屏或常驻层回调启停，初始化完成就开始守护
            configSyncWatchdog?.ensureRunning()
            if (BuildConfig.DEBUG) {
                startFdMonitor()
            }
            Log.i(TAG, "MonitorRuntime initialized")
        }
    }

    /**
     * 启动常驻层：配置同步、上传等定时任务、按配置开关定位，并监听录屏总开关（recordEnabled）。
     * 只启动一次，之后不停止；重复调用无效果。回调在后台线程触发，注册后会立刻回调一次当前值。
     */
    fun startBackground(onRecordEnabledChanged: (Boolean) -> Unit) {
        synchronized(lock) {
            ensureInitializedLocked()
            if (backgroundStarted) return

            syncCoordinator?.start()

            val configRepository = GlobalContext.get().get<ConfigRepository>()
            val scope = backgroundScope ?: return
            scope.launch {
                configRepository.getConfigFlow()
                    .map { it.locationTrack }
                    .distinctUntilChanged()
                    .collectLatest { cfg ->
                        applyLocationTrackConfig(cfg)
                    }
            }
            scope.launch {
                configRepository.getConfigFlow()
                    .map { it.recordEnabled }
                    .distinctUntilChanged()
                    .collect { onRecordEnabledChanged(it) }
            }

            backgroundStarted = true
            Log.i(TAG, "background started")
        }
    }

    /** 开启屏幕识别（随录屏）。首次调用时加载 OpenCV，失败会抛异常，下次调用重试 */
    fun startScreen() {
        synchronized(lock) {
            ensureInitializedLocked()
            if (screenStarted) return

            MonitorService.init(appContext!!)
            val service = MonitorService.getInstance()
            monitorService = service
            service.start()
            screenStarted = true
        }
    }

    /** 关闭屏幕识别（随录屏），不影响常驻层 */
    fun stopScreen() {
        synchronized(lock) {
            if (!screenStarted) return
            try {
                monitorService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Stop monitor service failed: ${e.message}")
            }
            screenStarted = false
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

    /**
     * 检查配置拉取是否健康（供 WorkManager 兜底任务调用）：看门狗循环不在或心跳过期就重建，
     * 配置过期或从未拉取成功就立即拉一次。返回当前是否健康。
     */
    suspend fun checkConfigSync(): Boolean {
        val watchdog = synchronized(lock) {
            ensureInitializedLocked()
            configSyncWatchdog
        } ?: return false
        if (watchdog.ensureRunning()) {
            Log.w(TAG, "config sync watchdog was not running, restarted")
        }
        return watchdog.checkNow()
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

        if (!config.enabled) {
            if (wasStarted) {
                stopAmapTrack()
                Log.i(TAG, "Location track disabled by config, collector stopped")
            }
            return
        }

        if (!wasStarted) {
            startAmapTrackInternal(options)
            Log.i(TAG, "Location track collector started by config: $options")
            return
        }

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

    // 已停用：native 库 yoyo_runtime 不再编译，见 com/tools/yoyo/NativeLib.kt
    // fun writeHardwareBufferToBuffer(
    //     hardwareBuffer: HardwareBuffer,
    //     dest: ByteBuffer
    // ): Int {
    //     return RuntimeNativeLib.nativeWriteHardwareBufferToBuffer(hardwareBuffer, dest)
    // }

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
