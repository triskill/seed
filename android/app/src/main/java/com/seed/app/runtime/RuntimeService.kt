package com.seed.app.runtime

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.seed.app.MainActivity
import com.seed.app.R
import com.seed.app.data.ApiModule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

/** Foreground owner of the embedded proot + FastAPI runtime. */
class RuntimeService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var supervisor: RuntimeSupervisor
    private val binder by lazy {
        RuntimeBinder(
            supervisor = supervisor,
            stopService = ::stopSelf,
        )
    }

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, runtimeNotification())

        supervisor = RuntimeSupervisor(
            scope = serviceScope,
            startProcess = {
                val runner = ProotRunner(
                    prootExecutable = NativeProot.executable(applicationInfo.nativeLibraryDir),
                    rootfsDir = File(File(filesDir, LINUX_DIRECTORY), ROOTFS_DIRECTORY),
                    env = RUNTIME_ENVIRONMENT,
                )
                runner.start(serviceScope).also(::collectRuntimeLogs)
            },
            healthStates = { HealthMonitor(ApiModule.embedded).states() },
            onFailure = { message, failure -> Log.e(TAG, message, failure) },
        )
        supervisor.startOrRetry()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
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
        const val CHANNEL_ID = "seed_runtime"
        const val NOTIFICATION_ID = 1001

        private const val TAG = "SeedRuntime"
        private const val LINUX_DIRECTORY = "linux"
        private const val ROOTFS_DIRECTORY = "rootfs"

        private val RUNTIME_ENVIRONMENT = mapOf(
            "HOME" to "/root",
            "LANG" to "C.UTF-8",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TERM" to "dumb",
        )
    }
}
