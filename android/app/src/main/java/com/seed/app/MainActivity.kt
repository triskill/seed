package com.seed.app

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
import com.seed.app.runtime.AndroidAssetSource
import com.seed.app.runtime.BootController
import com.seed.app.runtime.BootState
import com.seed.app.runtime.ExtractionScreen
import com.seed.app.runtime.HealthState
import com.seed.app.runtime.RootfsVersion
import com.seed.app.runtime.RuntimeBinder
import com.seed.app.runtime.RuntimeService
import com.seed.app.runtime.RuntimeStartupGate
import com.seed.app.runtime.StartRuntimeScreen
import com.seed.app.runtime.StartupDestination
import com.seed.app.runtime.resolveStartupDestination
import com.seed.app.runtime.shouldRequestRuntimeNotificationPermission
import com.seed.app.ui.nav.SeedNav
import com.seed.app.ui.theme.SeedTheme
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
    private var runtimeBinder: RuntimeBinder? = null
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

    private val runtimeConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (activityDestroyed || !frameworkBindingRegistered || !acceptedBinding) return

            if (!service.isBinderAlive || !service.pingBinder()) {
                rejectCurrentBinding(R.string.runtime_binding_dead)
                return
            }

            val binder = service as? RuntimeBinder
            if (binder == null) {
                rejectCurrentBinding(R.string.runtime_binding_invalid)
                return
            }

            clearRuntimeBinder()
            runtimeBinder = binder
            binderHealthJob = lifecycleScope.launch {
                binder.health.collect { health ->
                    if (!activityDestroyed && runtimeBinder === binder) {
                        runtimeHealth.value = health
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            if (activityDestroyed || !frameworkBindingRegistered || !acceptedBinding) return
            clearRuntimeBinder()
            publishRuntimeError(R.string.runtime_service_disconnected)
            // The platform keeps this binding active and may reconnect it.
        }

        override fun onBindingDied(name: ComponentName) {
            if (activityDestroyed || !frameworkBindingRegistered || !acceptedBinding) return
            clearRuntimeBinder()
            releaseFrameworkBinding()
            publishRuntimeError(R.string.runtime_binding_died)
        }

        override fun onNullBinding(name: ComponentName) {
            if (activityDestroyed || !frameworkBindingRegistered || !acceptedBinding) return
            clearRuntimeBinder()
            releaseFrameworkBinding()
            publishRuntimeError(R.string.runtime_binding_null)
        }
    }

    private val runtimeStartupGate = RuntimeStartupGate(::startAndBindRuntime)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        notificationPermissionRequested = getPreferences(MODE_PRIVATE)
            .getBoolean(NOTIFICATION_PERMISSION_REQUESTED_KEY, false)

        val targetDir = File(filesDir, "linux")
        val assetSource = AndroidAssetSource(assets)
        val assetVersion = assets.open("linux/seed_version.json").bufferedReader()
            .use { RootfsVersion.parse(it.readText()) }
        val bootController = BootController(
            targetDir = targetDir,
            source = assetSource,
            assetVersion = assetVersion,
            scope = lifecycleScope,
        )

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

                when (val destination = resolveStartupDestination(bootState, healthState)) {
                    is StartupDestination.Extraction -> ExtractionScreen(destination.state)
                    is StartupDestination.Runtime -> StartRuntimeScreen(
                        health = destination.health,
                        onRetry = ::retryRuntime,
                    )

                    StartupDestination.Seed -> SeedNav()
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

    private fun startAndBindRuntime() {
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

        try {
            try {
                ContextCompat.startForegroundService(this, serviceIntent)
            } catch (failure: Exception) {
                Log.e(TAG, "Failed to start runtime foreground service", failure)
                publishRuntimeError(R.string.runtime_service_start_failed)
                return
            }

            acceptedBinding = false
            frameworkBindingRegistered = true
            val accepted = try {
                bindService(serviceIntent, runtimeConnection, Context.BIND_AUTO_CREATE)
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

    private fun rejectCurrentBinding(messageRes: Int) {
        clearRuntimeBinder()
        releaseFrameworkBinding()
        publishRuntimeError(messageRes)
    }

    private fun clearRuntimeBinder() {
        runtimeBinder = null
        binderHealthJob?.cancel()
        binderHealthJob = null
    }

    private fun releaseFrameworkBinding() {
        acceptedBinding = false
        if (!frameworkBindingRegistered) return

        frameworkBindingRegistered = false
        try {
            unbindService(runtimeConnection)
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
