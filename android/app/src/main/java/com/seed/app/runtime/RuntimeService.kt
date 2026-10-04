package com.seed.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.util.Base64
import android.util.Log
import com.seed.app.MainActivity
import com.seed.app.R
import com.seed.app.data.AndroidSettingsRepo
import com.seed.app.data.ApiModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.security.SecureRandom

/** Foreground owner of the embedded proot + FastAPI runtime. */
class RuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var supervisor: RuntimeSupervisor
    private lateinit var terminalManager: SeedTerminalManager
    private var dnsCallback: ConnectivityManager.NetworkCallback? = null
    private lateinit var processFactory: OwnedRuntimeProcessFactory
    private lateinit var restoreCoordinator: RuntimeRestoreCoordinator
    @Volatile private var maintenanceOnly = false
    private val binder by lazy {
        RuntimeBinder(
            supervisor = supervisor,
            terminalManager = terminalManager,
            stopService = ::stopSelf,
            restoreState = restoreCoordinator.state,
            restoreRuntime = { serviceScope.launch { restoreCoordinator.restore() } },
            launchAllowed = { !maintenanceOnly },
        )
    }

    override fun onCreate() {
        super.onCreate()
        controlCapability = newControlCapability()
        startForeground(NOTIFICATION_ID, runtimeNotification())

        terminalManager = synchronized(terminalOwnerMonitor) {
            retainedTerminalManager ?: SeedTerminalManager(applicationContext).also { retainedTerminalManager = it }
        }
        val connectivity = getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                    // Do not let an obsolete network replace the active network's DNS.
                    if (network == connectivity.activeNetwork) {
                        try {
                            RuntimeMaintenanceGate.withWriter {
                                recoverRuntimeRestore(File(filesDir, LINUX_DIRECTORY))
                                GuestDns.write(File(filesDir, "$LINUX_DIRECTORY/$ROOTFS_DIRECTORY"), linkProperties.dnsServers)
                            }
                        } catch (failure: Exception) {
                            Log.w(TAG, "Could not update guest DNS", failure)
                        }
                    }
                }
            }
            try {
                connectivity.registerDefaultNetworkCallback(callback)
                dnsCallback = callback
            } catch (failure: Exception) {
                Log.w(TAG, "Could not observe DNS changes", failure)
            }
        }
        processFactory = androidOwnedRuntimeProcessFactory(
            receiptRoot = File(cacheDir, "runtime-ownership"),
            onFailure = { failure -> Log.e(TAG, "Runtime ownership/cleanup failed", failure) },
        )
        supervisor = createSupervisor()
        restoreCoordinator = RuntimeRestoreCoordinator(
            linux = File(filesDir, LINUX_DIRECTORY),
            source = AndroidAssetSource(assets),
            version = trustedVersion(),
            freeze = {
                maintenanceOnly = true
                RuntimeMaintenanceGate.freezeWriters()
            },
            stop = {
                kotlinx.coroutines.withContext(Dispatchers.Main) { terminalManager.freeze() }
                val backendStopped = supervisor.quiesce(10_000)
                val factoryStopped = processFactory.freezeAndStopConfirmed(10_000)
                val terminalStopped = terminalManager.stopAndAwaitExit(10_000)
                (backendStopped && factoryStopped && terminalStopped).also {
                    if (it) RuntimeMaintenanceGate.markRuntimeStopped()
                }
            },
            resume = {
                RuntimeMaintenanceGate.resumeWriters()
                processFactory.resumeLaunches()
                // Main-thread terminal admission is reopened after the exclusion gate releases.
                serviceScope.launch(Dispatchers.Main) { terminalManager.unfreeze() }
                supervisor = createSupervisor()
                binder.replaceSupervisor(supervisor)
                maintenanceOnly = false
                serviceScope.launch { supervisor.startOrRetry() }
            },
        )
    }

    private fun trustedVersion(): RootfsVersion = assets.open("linux/seed_version.json")
        .bufferedReader().use { RootfsVersion.parse(it.readText()) }

    private fun createSupervisor(): RuntimeSupervisor = RuntimeSupervisor(
            scope = serviceScope,
            startProcess = {
                RuntimeMaintenanceGate.withWriter {
                check(!maintenanceOnly) { "Runtime is frozen for Restore" }
                recoverRuntimeRestore(File(filesDir, LINUX_DIRECTORY))
                val nativeProot = NativeProot.resolve(applicationInfo.nativeLibraryDir)
                val runtimeDir = File(filesDir, LINUX_DIRECTORY)
                BackendPatchInstaller.install(File(runtimeDir, ROOTFS_DIRECTORY)) { module ->
                    assets.open("backend-patches/$module").use { it.readBytes() }
                }
                AgentPromptInstaller.install(File(runtimeDir, ROOTFS_DIRECTORY)) { role ->
                    assets.open("agent-prompts/$role").use { it.readBytes() }
                }
                GuestDns.sync(this@RuntimeService, File(runtimeDir, ROOTFS_DIRECTORY))
                val baseEnvironment = ProotEnvironment.createBackend(
                    tempDir = File(cacheDir, PROOT_TEMP_DIRECTORY),
                    installation = nativeProot,
                ) + mapOf(
                    "SEED_RUNTIME_CAPABILITY" to controlCapability,
                    // The FastAPI service passes this to both agents for generated
                    // app verification; Flask itself is a separate :7778 process.
                    "SEED_APP_URL" to "http://127.0.0.1:7778",
                )
                val runner = ProotRunner(
                    prootExecutable = nativeProot.executable,
                    rootfsDir = File(runtimeDir, ROOTFS_DIRECTORY),
                    env = baseEnvironment,
                    factory = processFactory,
                )
                RuntimeMaintenanceGate.markRuntimeOwned()
                runner.start(serviceScope).also(::collectRuntimeLogs)
                }
            },
            healthStates = { HealthMonitor(ApiModule.embedded).continuousStates() },
            onFailure = { message, failure -> Log.e(TAG, message, failure) },
        )

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_MAINTENANCE && !maintenanceOnly) supervisor.startOrRetry()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder {
        if (intent?.action != ACTION_MAINTENANCE && !maintenanceOnly) supervisor.startOrRetry()
        return binder
    }

    override fun onDestroy() {
        dnsCallback?.let { callback ->
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        }
        terminalManager.close()
        binder.close()
        if (::supervisor.isInitialized) supervisor.stop()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun collectRuntimeLogs(handle: ProotHandle) {
        serviceScope.launch {
            handle.stdout.collect { line -> Log.i(TAG, line) }
        }
        serviceScope.launch {
            handle.stderr.collect { line -> Log.e(TAG, line) }
        }
    }

    private fun runtimeNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java)
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_seed)
            .setContentTitle(getString(R.string.runtime_notification_title))
            .setContentText(getString(R.string.runtime_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private val terminalOwnerMonitor = Any()
        private var retainedTerminalManager: SeedTerminalManager? = null
        const val ACTION_MAINTENANCE = "com.seed.app.runtime.MAINTENANCE"
        const val CHANNEL_ID = "seed_runtime"
        const val NOTIFICATION_ID = 1001
        @Volatile var controlCapability: String = ""
            private set

        private fun newControlCapability(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP)
        }

        private const val TAG = "SeedRuntime"
        private const val LINUX_DIRECTORY = "linux"
        private const val ROOTFS_DIRECTORY = "rootfs"
        private const val PROOT_TEMP_DIRECTORY = "proot"
    }
}
