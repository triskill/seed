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
    private data class Command(val generation: Long, val restart: Boolean = false)
    private val commands = Channel<Command>(capacity = Channel.CONFLATED)
    private val terminal = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var generation = 0L
    private var handle: ProotHandle? = null
    private var restartPending = false
    private val commandJob = scope.launch { processCommands() }

    val health: StateFlow<HealthState> = mutableHealth.asStateFlow()
    val isRuntimeAlive: Boolean
        get() = synchronized(lifecycleLock) {
            !terminal.get() && handle?.isAlive == true
        }

    /** Queues startup/retry work in [scope] and returns without spawning on the caller thread. */
    fun startOrRetry() {
        synchronized(lifecycleLock) {
            if (terminal.get() || restartPending) return
            generation += 1
            mutableHealth.value = HealthState.Unknown
            commands.trySend(Command(generation))
        }
    }

    /** Queue explicit replacement; retain ownership until the old handle exits. */
    fun restart() {
        synchronized(lifecycleLock) {
            if (terminal.get() || restartPending) return
            generation += 1
            mutableHealth.value = HealthState.Unknown
            restartPending = true
            commands.trySend(Command(generation, restart = true))
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
        var livenessWatch: Job? = null
        try {
            for (command in commands) {
                val commandGeneration = command.generation
                try {
                    livenessWatch?.cancelAndJoin()
                    livenessWatch = null
                    healthCollection?.cancelAndJoin()
                    healthCollection = null

                    if (!isCurrent(commandGeneration)) continue
                    if (command.restart && !stopBeforeReplacement(commandGeneration)) continue
                    val activeHandle = activeOrReplacement(commandGeneration) ?: continue
                    if (!isCurrent(commandGeneration)) continue
                    if (!activeHandle.isAlive) {
                        publishForHandle(commandGeneration, activeHandle, processExited())
                        continue
                    }

                    val collector = launch {
                        collectHealth(commandGeneration, activeHandle)
                    }
                    healthCollection = collector
                    livenessWatch = launch {
                        while (isCurrent(commandGeneration)) {
                            if (!activeHandle.isAlive) {
                                publishForHandle(commandGeneration, activeHandle, processExited())
                                collector.cancel()
                                break
                            }
                            delay(500)
                        }
                    }
                } finally {
                    if (command.restart) synchronized(lifecycleLock) { restartPending = false }
                }
            }
        } finally {
            livenessWatch?.cancel()
            healthCollection?.cancel()
        }
    }

    private suspend fun stopBeforeReplacement(commandGeneration: Long): Boolean {
        val previous = synchronized(lifecycleLock) { handle } ?: return true
        try {
            previous.destroy()
            if (!previous.awaitExit(10_000)) {
                publishIfCurrent(commandGeneration, HealthState.Unhealthy(
                    "Could not stop the previous runtime. Close and reopen Seed.",
                ))
                return false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure("Could not stop embedded runtime", failure)
            publishIfCurrent(commandGeneration, HealthState.Unhealthy("Could not stop the previous runtime"))
            return false
        }
        synchronized(lifecycleLock) {
            if (handle === previous && generation == commandGeneration && !terminal.get()) {
                handle = null
            }
        }
        return isCurrent(commandGeneration)
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

    private suspend fun collectHealth(commandGeneration: Long, activeHandle: ProotHandle) {
        try {
            healthStates().collect { state ->
                publishForHandle(commandGeneration, activeHandle, state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onFailure("Health check failed", failure)
            publishForHandle(
                commandGeneration,
                activeHandle,
                HealthState.Unhealthy(failure.message ?: "Health check failed"),
            )
        }
    }

    private fun processExited() = HealthState.Unhealthy("Embedded runtime process exited")

    /** A late HTTP response must not mask process death or affect a replacement. */
    private fun publishForHandle(
        commandGeneration: Long,
        activeHandle: ProotHandle,
        state: HealthState,
    ) {
        synchronized(lifecycleLock) {
            if (!terminal.get() && generation == commandGeneration && handle === activeHandle) {
                mutableHealth.value = if (activeHandle.isAlive) state else processExited()
            }
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
