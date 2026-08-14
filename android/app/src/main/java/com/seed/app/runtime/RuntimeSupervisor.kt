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
            commands.trySend(generation)
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
            if (terminal.get()) {
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
