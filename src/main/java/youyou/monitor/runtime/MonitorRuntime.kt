package youyou.monitor.runtime

import android.content.Context
import android.hardware.HardwareBuffer
import java.nio.ByteBuffer
import org.koin.core.context.GlobalContext
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.logger.Log
import youyou.monitor.screen.MonitorService
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.infra.repository.TemplateRepositoryImpl
import youyou.monitor.sync.config.ConfigRepositoryImpl
import youyou.monitor.sync.storage.StorageRepository
import youyou.monitor.sync.task.ScheduledTaskManager

object MonitorRuntime {
    private const val TAG = "MonitorRuntime"

    private val lock = Any()

    @Volatile
    private var initialized = false

    @Volatile
    private var started = false

    private var appContext: Context? = null
    private var monitorService: MonitorService? = null
    private var syncCoordinator: MonitorSyncCoordinator? = null

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
                syncCoordinator?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Stop sync coordinator failed: ${e.message}")
            }

            try {
                monitorService?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Stop monitor service failed: ${e.message}")
            }

            started = false
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

    fun onFrameAvailable(buffer: ByteBuffer, width: Int, height: Int, scale: Int = 1) {
        monitorService?.onFrameAvailable(buffer, width, height, scale)
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
}
