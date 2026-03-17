package youyou.monitor.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import youyou.monitor.config.model.WebDavServer
import youyou.monitor.config.repository.ConfigRepository
import youyou.monitor.logger.Log
import youyou.monitor.screen.core.domain.repository.TemplateRepository
import youyou.monitor.screen.infra.repository.TemplateRepositoryImpl
import youyou.monitor.sync.config.ConfigRepositoryImpl
import youyou.monitor.sync.storage.StorageRepository
import youyou.monitor.sync.task.ScheduledTaskManager
import youyou.monitor.webdav.WebDavClient

internal class MonitorSyncCoordinator(
    private val context: Context,
    private val configRepository: ConfigRepository,
    private val configRepositoryImpl: ConfigRepositoryImpl,
    private val templateRepository: TemplateRepository,
    private val templateRepositoryImpl: TemplateRepositoryImpl,
    private val storageRepository: StorageRepository,
    private val scheduledTaskManager: ScheduledTaskManager
) {

    companion object {
        private const val TAG = "MonitorSyncCoordinator"
    }

    @Volatile
    private var scope: CoroutineScope? = null

    @Volatile
    private var scopeJob: Job? = null

    private val scopeLock = Any()

    @Volatile
    private var isRunning = false

    private val configRetryLock = Any()
    private var configRetryAttempt = 0
    private val configRetryInitialMs = if (BuildConfig.DEBUG) 30_000L else 60_000L
    private val configRetryMaxMs = 6 * 60 * 60 * 1000L

    @Volatile
    private var currentWebDavClient: WebDavClient? = null
    private val webDavClientLock = Any()

    @Volatile
    private var onRootDirChanged: ((String) -> Unit)? = null

    @Volatile
    private var isNetworkCallbackRegistered = false

    init {
        configRepositoryImpl.setOnWebDavServersChanged { _, fastestServer, fastestClient ->
            if (!isRunning) {
                Log.d(TAG, "同步协调器未运行，跳过WebDAV重新配置")
                return@setOnWebDavServersChanged
            }

            try {
                getScope().launch {
                    if (fastestServer != null && fastestClient != null) {
                        configureWebDavDirect(fastestServer, fastestClient)
                    } else {
                        autoLoadConfiguration()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "启动WebDAV重新配置失败：${e.message}")
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var lastNetworkType: String? = null

        override fun onAvailable(network: Network) {
            Log.d(TAG, "网络可用: $network")
            checkNetworkChange()
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "网络丢失: $network")
            checkNetworkChange()
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            val currentType = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "OTHER"
            }

            if (currentType != lastNetworkType) {
                Log.i(TAG, "网络类型变化: $lastNetworkType -> $currentType")
                lastNetworkType = currentType

                if (isRunning) {
                    getScope().launch(Dispatchers.IO) {
                        try {
                            Log.i(TAG, "由于网络变化，正在重新评估WebDAV配置")
                            reconfigureWebDavForNetwork()
                        } catch (e: Exception) {
                            Log.e(TAG, "网络变化时重新配置WebDAV失败：${e.message}", e)
                        }
                    }
                }
            }
        }

        private fun checkNetworkChange() {
            if (isRunning) {
                getScope().launch(Dispatchers.IO) {
                    kotlinx.coroutines.delay(1000)
                    try {
                        reconfigureWebDavForNetwork()
                    } catch (e: Exception) {
                        Log.w(TAG, "网络变化重新配置失败：${e.message}")
                    }
                }
            }
        }
    }

    fun setOnRootDirChanged(callback: ((String) -> Unit)?) {
        onRootDirChanged = callback
    }

    fun start() {
        synchronized(this) {
            if (isRunning) {
                Log.w(TAG, "同步协调器已在运行中，跳过启动")
                return
            }
            isRunning = true
        }

        registerNetworkCallbackIfNeeded()
        startConfigMonitoring()

        getScope().launch(Dispatchers.IO) {
            try {
                val success = autoLoadConfiguration()
                if (!success && isRunning) {
                    scheduleRetryConfig()
                }
            } catch (e: Exception) {
                Log.e(TAG, "启动自动加载配置失败: ${e.message}", e)
            }
        }

        scheduledTaskManager.startAllTasks(
            configUpdateInterval = if (BuildConfig.DEBUG) 1 else 6 * 60,
            imageUploadInterval = if (BuildConfig.DEBUG) 60 else 5,
            videoUploadInterval = 10,
            logUploadInterval = 30,
            templateSyncInterval = 60,
            storageCleanInterval = 360
        )

        Log.i(TAG, "同步协调器已启动")
    }

    fun stop() {
        synchronized(this) {
            if (!isRunning) {
                Log.w(TAG, "同步协调器已停止，跳过停止操作")
                return
            }
            isRunning = false
        }

        unregisterNetworkCallbackIfNeeded()

        scope?.cancel()
        scope = null
        scopeJob = null

        scheduledTaskManager.shutdown()

        val clientToClose = synchronized(webDavClientLock) {
            val client = currentWebDavClient
            currentWebDavClient = null
            client
        }
        clientToClose?.close()

        Log.i(TAG, "同步协调器已停止")
    }

    private fun startConfigMonitoring() {
        getScope().launch {
            configRepository.getConfigFlow()
                .distinctUntilChanged()
                .collect { config ->
                    storageRepository.updateConfig(config)
                    templateRepository.updateConfig(config)

                    try {
                        onRootDirChanged?.invoke(storageRepository.getRootDirPath())
                    } catch (e: Exception) {
                        Log.w(TAG, "通知根目录变化失败: ${e.message}")
                    }
                }
        }
    }

    private fun getScope(): CoroutineScope {
        val currentScope = scope
        val currentJob = scopeJob
        if (currentScope != null && currentJob != null && currentJob.isActive) {
            return currentScope
        }

        return synchronized(scopeLock) {
            val existingScope = scope
            val existingJob = scopeJob

            if (existingScope != null && existingJob != null && existingJob.isActive) {
                existingScope
            } else {
                val newJob = SupervisorJob()
                val dispatcher = if (isRunning) Dispatchers.Main else Dispatchers.Default
                val newScope = CoroutineScope(dispatcher + newJob)
                scope = newScope
                scopeJob = newJob
                newScope
            }
        }
    }

    private fun registerNetworkCallbackIfNeeded() {
        if (isNetworkCallbackRegistered) return

        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val networkRequest = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(networkRequest, networkCallback)
            isNetworkCallbackRegistered = true
            Log.d(TAG, "网络变化监听器已注册")
        } catch (e: Exception) {
            Log.w(TAG, "注册网络回调失败: ${e.message}")
        }
    }

    private fun unregisterNetworkCallbackIfNeeded() {
        if (!isNetworkCallbackRegistered) return

        try {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isNetworkCallbackRegistered = false
            Log.d(TAG, "网络变化监听器已取消注册")
        } catch (e: Exception) {
            Log.w(TAG, "取消注册网络回调失败: ${e.message}")
        }
    }

    private suspend fun configureWebDavDirect(
        server: WebDavServer,
        client: WebDavClient
    ) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "正在使用最快的WebDAV服务器: ${server.url}")

            val oldClient = synchronized(webDavClientLock) {
                val old = currentWebDavClient
                currentWebDavClient = client
                old
            }
            oldClient?.close()

            configRepositoryImpl.setWebDavClient(client)
            templateRepositoryImpl.setWebDavClient(client, server.templateDir)
            scheduledTaskManager.setWebDavClient(client)

            try {
                val latestConfig = configRepository.getCurrentConfig()
                templateRepository.updateConfig(latestConfig)
            } catch (e: Exception) {
                Log.w(TAG, "下发最新配置给TemplateRepository失败: ${e.message}")
            }

            Log.i(TAG, "WebDAV已配置最快服务器")
            synchronized(configRetryLock) { configRetryAttempt = 0 }
        } catch (e: Exception) {
            Log.e(TAG, "配置WebDAV失败: ${e.message}", e)
        }
    }

    private fun scheduleRetryConfig() {
        synchronized(configRetryLock) {
            if (!isRunning) return

            val attempt = configRetryAttempt
            val calc = try {
                configRetryInitialMs * (1L shl attempt)
            } catch (_: Exception) {
                configRetryMaxMs
            }
            val delayMs = kotlin.math.min(calc, configRetryMaxMs)
            configRetryAttempt = attempt + 1

            Log.i(TAG, "配置加载失败，计划在 ${delayMs / 1000}s 后重试 (attempt=${configRetryAttempt})")

            getScope().launch(Dispatchers.IO) {
                try {
                    kotlinx.coroutines.delay(delayMs)
                    if (!isRunning) return@launch

                    val success = autoLoadConfiguration()
                    if (!success && isRunning) {
                        scheduleRetryConfig()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "配置重试任务失败: ${e.message}")
                }
            }
        }
    }

    private suspend fun autoLoadConfiguration() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== autoLoadConfiguration 开始 ===")

            val syncResult = configRepository.syncFromRemote()
            if (syncResult.isSuccess) {
                Log.i(TAG, "远程配置已同步，WebDAV通过回调自动配置")
                synchronized(configRetryLock) { configRetryAttempt = 0 }
                return@withContext true
            }

            Log.w(TAG, "远程同步失败: ${syncResult.exceptionOrNull()?.message}，尝试本地配置")

            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.w(TAG, "未配置WebDAV服务器")
                return@withContext false
            }

            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue

                var client: WebDavClient? = null
                try {
                    client = configRepositoryImpl.createWebDavClient(server)

                    Log.d(TAG, "正在测试降级服务器: ${server.url}")
                    if (client.testConnection()) {
                        Log.i(TAG, "正在配置降级服务器: ${server.url}")
                        configureWebDavDirect(server, client)
                        synchronized(configRetryLock) { configRetryAttempt = 0 }
                        return@withContext true
                    } else {
                        Log.w(TAG, "降级服务器 ${server.url} 不可用")
                        client.close()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试降级服务器失败 ${server.url}: ${e.message}")
                    client?.close()
                }
            }

            Log.w(TAG, "所有降级服务器都失败")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "autoLoadConfiguration失败: ${e.message}", e)
            return@withContext false
        }
    }

    private suspend fun reconfigureWebDavForNetwork() = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "=== reconfigureWebDavForNetwork 开始 ===")

            val config = configRepository.getCurrentConfig()
            if (config.webdavServers.isEmpty()) {
                Log.d(TAG, "未配置WebDAV服务器，跳过重新配置")
                return@withContext
            }

            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)

            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            Log.i(TAG, "当前网络 - WiFi: $isWifi, 移动数据: $isCellular")

            var fastestServer: WebDavServer? = null
            var fastestClient: WebDavClient? = null
            var fastestResponseTime = Long.MAX_VALUE

            for (server in config.webdavServers) {
                if (server.url.isEmpty()) continue

                var client: WebDavClient? = null
                try {
                    client = configRepositoryImpl.createWebDavClient(server)

                    val startTime = System.currentTimeMillis()
                    val isAvailable = client.testConnection()
                    val responseTime = System.currentTimeMillis() - startTime

                    if (isAvailable && responseTime < fastestResponseTime) {
                        fastestResponseTime = responseTime
                        fastestServer = server
                        fastestClient?.close()
                        fastestClient = client
                        client = null
                        Log.d(TAG, "发现更快的服务器: ${server.url} (${responseTime}ms)")
                    } else {
                        Log.d(
                            TAG,
                            "服务器 ${server.url} ${if (isAvailable) "可用 (${responseTime}ms)" else "不可用"}"
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "测试服务器失败 ${server.url}: ${e.message}")
                } finally {
                    client?.close()
                }
            }

            if (fastestServer != null && fastestClient != null) {
                Log.i(
                    TAG,
                    "为当前网络重新配置最快服务器: ${fastestServer.url} (${fastestResponseTime}ms)"
                )
                configureWebDavDirect(fastestServer, fastestClient)
            } else {
                Log.w(TAG, "当前网络下未找到可用的WebDAV服务器")
            }
        } catch (e: Exception) {
            Log.e(TAG, "reconfigureWebDavForNetwork失败: ${e.message}", e)
        }
    }
}
