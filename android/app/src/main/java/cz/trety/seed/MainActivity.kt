package cz.trety.seed

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cz.trety.seed.runtime.AndroidAssetSource
import cz.trety.seed.runtime.BootController
import cz.trety.seed.runtime.BootState
import cz.trety.seed.runtime.ExtractionScreen
import cz.trety.seed.runtime.HealthState
import cz.trety.seed.runtime.RootfsVersion
import cz.trety.seed.runtime.RuntimeBindingTimeout
import cz.trety.seed.runtime.RuntimeBinder
import cz.trety.seed.runtime.RuntimeService
import cz.trety.seed.runtime.SeedTerminalManager
import cz.trety.seed.runtime.RuntimeStartupGate
import cz.trety.seed.runtime.StartRuntimeScreen
import cz.trety.seed.runtime.StartupDestination
import cz.trety.seed.runtime.resolveStartupDestination
import cz.trety.seed.runtime.shouldRequestRuntimeNotificationPermission
import cz.trety.seed.ui.app.RetainedAppNavigation
import cz.trety.seed.ui.nav.SeedNav
import cz.trety.seed.ui.theme.SeedTheme
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Thin lifecycle adapter between runtime extraction, [RuntimeService], and Compose.
 *
 * [BootController] owns extraction, the bound service owns process health, and the
 * tested startup gate/resolver decide when the service may start and which UI is
 * visible. This activity only mirrors those flows, retains one service binding,
 * and releases that binding on destruction without stopping the foreground service.
 */
class MainActivity : ComponentActivity() {
    private val runtimeHealth = MutableStateFlow<HealthState>(HealthState.Unknown)
    private val restoreState = MutableStateFlow<cz.trety.seed.runtime.RestoreState>(cz.trety.seed.runtime.RestoreState.Idle)
    private var restoreJob: Job? = null
    private var restoreAfterBinding = false
    private lateinit var bootController: BootController
    private var runtimeBinder: RuntimeBinder? = null
    private var terminalManager: SeedTerminalManager? = null
    private var binderHealthJob: Job? = null
    private var frameworkBindingRegistered = false
    private var acceptedBinding = false
    private var notificationPermissionRequested = false
    private var activityDestroyed = false

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        // Permission denial does not gate or stop the foreground runtime.
    }

    private var runtimeConnection: ServiceConnection? = null
    private val bindingTimeout = RuntimeBindingTimeout(lifecycleScope) {
        if (!activityDestroyed && acceptedBinding && runtimeBinder == null) {
            rejectCurrentBinding(R.string.runtime_binding_timeout)
        }
    }

    private fun newRuntimeConnection() = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!isCurrentConnection(this)) return

            if (!service.isBinderAlive || !service.pingBinder()) {
                rejectCurrentBinding(R.string.runtime_binding_dead)
                return
            }

            val binder = service as? RuntimeBinder
            if (binder == null) {
                rejectCurrentBinding(R.string.runtime_binding_invalid)
                return
            }

            bindingTimeout.cancel()
            clearRuntimeBinder()
            runtimeBinder = binder
            terminalManager = binder.terminalManager
            restoreJob = lifecycleScope.launch { binder.restoreState.collect {
                restoreState.value = it
                if (it is cz.trety.seed.runtime.RestoreState.Finished) bootController.refreshAfterRestore()
            } }
            if (restoreAfterBinding) { restoreAfterBinding = false; binder.restore() }
            binderHealthJob = lifecycleScope.launch {
                binder.health.collect { health ->
                    if (!activityDestroyed && runtimeBinder === binder) {
                        runtimeHealth.value = health
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (!isCurrentConnection(this)) return
            clearRuntimeBinder()
            publishRuntimeError(R.string.runtime_service_disconnected)
            // The platform keeps this binding active and may reconnect it.
        }

        override fun onBindingDied(name: ComponentName) {
            if (!isCurrentConnection(this)) return
            clearRuntimeBinder()
            releaseFrameworkBinding()
            publishRuntimeError(R.string.runtime_binding_died)
        }

        override fun onNullBinding(name: ComponentName) {
            if (!isCurrentConnection(this)) return
            clearRuntimeBinder()
            releaseFrameworkBinding()
            publishRuntimeError(R.string.runtime_binding_null)
        }
    }

    private fun isCurrentConnection(connection: ServiceConnection): Boolean =
        !activityDestroyed && frameworkBindingRegistered && acceptedBinding &&
            runtimeConnection === connection

    private val runtimeStartupGate = RuntimeStartupGate { startAndBindRuntime() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionRequested = getPreferences(MODE_PRIVATE)
            .getBoolean(NOTIFICATION_PERMISSION_REQUESTED_KEY, false)

        val targetDir = File(filesDir, "linux")
        val assetSource = AndroidAssetSource(assets)
        val assetVersion = assets.open("linux/seed_version.json").bufferedReader()
            .use { RootfsVersion.parse(it.readText()) }
        bootController = BootController(
            targetDir = targetDir,
            source = assetSource,
            assetVersion = assetVersion,
            scope = lifecycleScope,
            onFailure = { failure -> Log.e(TAG, "Runtime preparation failed", failure) },
        )

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                cz.trety.seed.runtime.RuntimeMaintenanceGate.active.collect { active ->
                    if (cz.trety.seed.runtime.shouldBindMaintenance(bootController.states.value, active)) {
                        startAndBindRuntime(maintenance = true)
                    }
                }
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                bootController.states.collect { state ->
                    if (state is BootState.NeedsExtraction) {
                        bootController.runExtraction()
                    }
                    runtimeStartupGate.update(state)
                }
            }
        }

        setContent {
            SeedTheme {
                val bootState by bootController.states.collectAsState()
                val healthState by runtimeHealth.collectAsState()
                val restoration by restoreState.collectAsState()
                val maintenanceActive by cz.trety.seed.runtime.RuntimeMaintenanceGate.active.collectAsState()
                val destination = resolveStartupDestination(bootState, healthState)
                RetainedAppNavigation(
                    ready = destination is StartupDestination.Seed && cz.trety.seed.runtime.rootScreensAllowed(restoration, maintenanceActive),
                    waiting = {
                        if (!cz.trety.seed.runtime.rootScreensAllowed(restoration, maintenanceActive)) {
                            androidx.activity.compose.BackHandler { }
                            cz.trety.seed.ui.settings.RuntimeRestoreSettings(restoration, ::restoreRuntime)
                        } else when (destination) {
                            is StartupDestination.Extraction -> androidx.compose.foundation.layout.Column {
                                cz.trety.seed.ui.settings.RuntimeRestoreSettings(restoration, ::restoreRuntime)
                                ExtractionScreen(state = destination.state, onRetry = bootController::runExtraction)
                            }
                            is StartupDestination.Runtime -> androidx.compose.foundation.layout.Column {
                                cz.trety.seed.ui.settings.RuntimeRestoreSettings(restoration, ::restoreRuntime)
                                StartRuntimeScreen(
                                    health = destination.health,
                                    onRetry = ::retryRuntime,
                                    onRestart = if (runtimeBinder?.isBinderAlive == true) ::restartRuntime else null,
                                )
                            }
                            is StartupDestination.Seed -> cz.trety.seed.ui.settings.RuntimeRestoreSettings(restoration, ::restoreRuntime)
                        }
                    },
                ) {
                    SeedNav(
                        restoration = restoration,
                        onRestore = ::restoreRuntime,
                        terminalManager = terminalManager
                            ?: throw IllegalStateException("Terminal manager not bound when navigating to Seed"),
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        activityDestroyed = true
        clearRuntimeBinder()
        releaseFrameworkBinding()
        super.onDestroy()
    }

    private fun restoreRuntime() {
        val binder = runtimeBinder
        if (binder?.isBinderAlive == true) { binder.restore(); return }
        restoreAfterBinding = true
        startAndBindRuntime(maintenance = true)
    }

    private fun startAndBindRuntime(maintenance: Boolean = false) {
        if (
            activityDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            frameworkBindingRegistered ||
            acceptedBinding
        ) {
            return
        }

        clearRuntimeBinder()
        runtimeHealth.value = HealthState.Unknown
        val serviceIntent = Intent(this, RuntimeService::class.java)
        if (maintenance) serviceIntent.action = RuntimeService.ACTION_MAINTENANCE

        try {
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to start runtime foreground service", failure)
                publishRuntimeError(R.string.runtime_service_start_failed)
                return
            }

            acceptedBinding = false
            val connection = newRuntimeConnection()
            runtimeConnection = connection
            frameworkBindingRegistered = true
            val accepted = try {
                bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to bind runtime service", failure)
                releaseFrameworkBinding()
                publishRuntimeError(R.string.runtime_binding_failed)
                return
            }
            acceptedBinding = accepted
            if (!accepted) {
                releaseFrameworkBinding()
                publishRuntimeError(R.string.runtime_binding_rejected)
            } else {
                bindingTimeout.arm()
            }
        } finally {
            requestRuntimeNotificationPermissionIfNeeded()
        }
    }

    private fun retryRuntime() {
        val binder = runtimeBinder
        if (binder != null && binder.isBinderAlive) {
            binder.retry()
            return
        }

        clearRuntimeBinder()
        releaseFrameworkBinding()
        startAndBindRuntime()
    }

    private fun restartRuntime() {
        val binder = runtimeBinder
        if (binder == null || !binder.isBinderAlive) {
            retryRuntime()
            return
        }
        runtimeHealth.value = HealthState.Unknown
        binder.restart()
    }

    private fun rejectCurrentBinding(messageRes: Int) {
        clearRuntimeBinder()
        releaseFrameworkBinding()
        publishRuntimeError(messageRes)
    }

    private fun clearRuntimeBinder() {
        runtimeBinder = null
        terminalManager = null
        binderHealthJob?.cancel()
        binderHealthJob = null
        restoreJob?.cancel()
        restoreJob = null
    }

    private fun releaseFrameworkBinding() {
        bindingTimeout.cancel()
        acceptedBinding = false
        val connection = runtimeConnection
        runtimeConnection = null
        if (!frameworkBindingRegistered) return

        frameworkBindingRegistered = false
        if (connection == null) return
        try {
            unbindService(connection)
        } catch (_: IllegalArgumentException) {
            // The framework already discarded the connection; local state is released.
        }
    }

    private fun publishRuntimeError(messageRes: Int) {
        if (!activityDestroyed) {
            runtimeHealth.value = HealthState.Unhealthy(getString(messageRes))
        }
    }

    private fun requestRuntimeNotificationPermissionIfNeeded() {
        if (
            activityDestroyed ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (
            !shouldRequestRuntimeNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                granted = granted,
                alreadyRequested = notificationPermissionRequested,
            )
        ) {
            return
        }

        val preferences = getPreferences(MODE_PRIVATE)
        notificationPermissionRequested = true
        preferences.edit()
            .putBoolean(NOTIFICATION_PERMISSION_REQUESTED_KEY, true)
            .apply()
        try {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (failure: Exception) {
            Log.e(TAG, "Failed to launch notification permission request", failure)
            notificationPermissionRequested = false
            try {
                preferences.edit()
                    .putBoolean(NOTIFICATION_PERMISSION_REQUESTED_KEY, false)
                    .apply()
            } catch (rollbackFailure: Exception) {
                Log.e(TAG, "Failed to roll back notification permission marker", rollbackFailure)
            }
        }
    }

    private companion object {
        const val TAG = "MainActivity"
        const val NOTIFICATION_PERMISSION_REQUESTED_KEY =
            "runtime_notification_permission_requested"
    }
}
