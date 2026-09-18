package com.seed.app.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

internal class RuntimeSupervisor(
    private val scope: CoroutineScope,
    private val startProcess: suspend () -> ProotHandle,
    private val healthStates: () -> Flow<HealthState>,
    private val onFailure: (message: String, failure: Throwable) -> Unit = { _, _ -> },
) {
    private val mutableHealth = MutableStateFlow<HealthState>(HealthState.Unknown)
    private val commands = Channel<Long>(capacity = Channel.CONFLATED)
    private val terminal = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var generation = 0L
    private var handle: ProotHandle? = null
    private var restartPending = false
    private var controlOnly: Boolean = false
    private val commandJob = scope.launch { processCommands() }

    val health: StateFlow<HealthState> = mutableHealth.asStateFlow()
    val isRuntimeAlive: Boolean
        get() = synchronized(lifecycleLock) {
            !terminal.get() && handle?.isAlive == true
        }

    /** Queues startup/retry work in [scope] and returns without spawning on the caller thread. */
    fun startOrRetry() {
        synchronized(lifecycleLock) {
            if (terminal.get()) return
            generation += 1
            mutableHealth.value = HealthState.Unknown
            if (!restartPending) commands.trySend(generation)
        }
    }

    /** Replace the current PRoot generation while keeping the service alive. */
    fun restart() = replaceGeneration(controlOnly)

    /** Switch into control-only mode and replace the current PRoot generation. */
    fun startControlOnly() = replaceGeneration(true)

    /** Switch back to the normal orchestrator and replace the current generation.
     *  No-op when the runtime is already in normal mode, so a leave callback
     *  after a save (which already flipped the mode via restartWithMode) does
     *  not trigger a redundant respawn. */
    fun startNormal() {
        if (!isControlOnly()) return
        replaceGeneration(false)
    }

    /** Replace the current generation with one in [nextControlOnly] mode in a
     *  single transition. Use this from the Settings apply path so saving a
     *  model and leaving Settings produces one restart (not three): apply
     *  flips the mode and the leave callback's startNormal() then finds the
     *  runtime already in normal mode and is a no-op. */
    fun restartWithMode(nextControlOnly: Boolean) = replaceGeneration(nextControlOnly)

    /** True when the next (or current) generation should be control-only. */
    fun isControlOnly(): Boolean = synchronized(lifecycleLock) { controlOnly }

    /** Bump the generation, tear down the active handle, queue a new process start
     *  once the old handle is actually dead. Used by every transition that must
     *  take effect on the running PRoot process (mode change, settings apply). */
    private fun replaceGeneration(nextControlOnly: Boolean) {
        val activeHandle = synchronized(lifecycleLock) {
            if (terminal.get()) return
            controlOnly = nextControlOnly
            generation += 1
            mutableHealth.value = HealthState.Unknown
            if (restartPending) return
            restartPending = true
            handle.also { handle = null }
        }
        scope.launch {
            activeHandle?.destroy()
            // ProotHandle.destroy() escalates asynchronously. Do not start a
            // replacement until the old process is actually gone, otherwise
            // credentials and ports can overlap across generations.
            while (activeHandle?.isAlive == true) {
                if (terminal.get()) return@launch
                delay(50)
            }
            synchronized(lifecycleLock) {
                restartPending = false
                if (!terminal.get()) commands.trySend(generation)
            }
        }
    }

    /** Permanently stops this supervisor. Calls made after stop are ignored. */
    fun stop() {
        val activeHandle = synchronized(lifecycleLock) {
            if (!terminal.compareAndSet(false, true)) return
            generation += 1
            commands.close()
            commandJob.cancel()
            handle.also { handle = null }
        }
        activeHandle?.destroy()
    }

    private suspend fun processCommands() = coroutineScope {
        var healthCollection: Job? = null
        try {
            for (commandGeneration in commands) {
                healthCollection?.cancelAndJoin()
                healthCollection = null

                if (!isCurrent(commandGeneration)) continue
                val activeHandle = activeOrReplacement(commandGeneration) ?: continue
                if (!activeHandle.isAlive || !isCurrent(commandGeneration)) continue

                healthCollection = launch {
                    collectHealth(commandGeneration)
                }
            }
        } finally {
            healthCollection?.cancel()
        }
    }

    private suspend fun activeOrReplacement(commandGeneration: Long): ProotHandle? {
        val currentHandle = synchronized(lifecycleLock) { handle }
        if (currentHandle?.isAlive == true) return currentHandle

        val staleHandle = synchronized(lifecycleLock) {
            if (handle === currentHandle) {
                handle = null
                currentHandle
            } else {
                null
            }
        }
        staleHandle?.destroy()
        if (!isCurrent(commandGeneration)) return null

        val replacement = try {
            startProcess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure("Could not start embedded runtime", failure)
            publishIfCurrent(
                commandGeneration,
                HealthState.Unhealthy(
                    failure.message ?: "Could not start embedded runtime",
                ),
            )
            return null
        }

        val installed = synchronized(lifecycleLock) {
            if (terminal.get() || generation != commandGeneration) {
                false
            } else {
                handle = replacement
                true
            }
        }
        if (!installed) {
            replacement.destroy()
            return null
        }
        return replacement
    }

    private suspend fun collectHealth(commandGeneration: Long) {
        try {
            healthStates().collect { state ->
                publishIfCurrent(commandGeneration, state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure("Health check failed", failure)
            publishIfCurrent(
                commandGeneration,
                HealthState.Unhealthy(failure.message ?: "Health check failed"),
            )
        }
    }

    private fun isCurrent(commandGeneration: Long): Boolean =
        synchronized(lifecycleLock) {
            !terminal.get() && generation == commandGeneration
        }

    private fun publishIfCurrent(commandGeneration: Long, state: HealthState) {
        synchronized(lifecycleLock) {
            if (!terminal.get() && generation == commandGeneration) {
                mutableHealth.value = state
            }
        }
    }
}
