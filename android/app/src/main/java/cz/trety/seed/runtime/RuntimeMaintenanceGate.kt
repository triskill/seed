package cz.trety.seed.runtime

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-wide exclusion. Synchronous writers fail closed during maintenance. */
object RuntimeMaintenanceGate {
    private val monitor = Any()
    private val maintenance = Mutex()
    private val mutableActive = kotlinx.coroutines.flow.MutableStateFlow(false)
    val active: kotlinx.coroutines.flow.StateFlow<Boolean> = mutableActive
    private fun publishActive() { mutableActive.value = excluded || frozen }
    private var excluded = false
    private var writing = false
    private var frozen = false
    private var runtimeOwned = false
    fun markRuntimeOwned() = synchronized(monitor) { runtimeOwned = true }
    fun markRuntimeStopped() = synchronized(monitor) { runtimeOwned = false }
    fun freezeWriters() = synchronized(monitor) { frozen = true; publishActive() }
    fun resumeWriters() = synchronized(monitor) { frozen = false; publishActive() }
    fun <T> withWriter(block: () -> T): T {
        synchronized(monitor) {
            check(!excluded && !frozen && !writing) { "Runtime writer or maintenance in progress" }
            writing = true
        }
        try { return block() } finally { synchronized(monitor) { writing = false } }
    }
    suspend fun <T> exclusive(allowFrozen: Boolean = false, block: suspend () -> T): T = maintenance.withLock {
        synchronized(monitor) {
            check(allowFrozen || (!frozen && !runtimeOwned)) { "Runtime may still be running; use Restore to stop it safely" }
            excluded = true
            publishActive()
        }
        try {
            // Never block Main behind IO/receipt handshakes. Existing admitted writers
            // finish before maintenance; new synchronous writers fail closed.
            while (synchronized(monitor) { writing }) kotlinx.coroutines.delay(10)
            synchronized(monitor) {
                check(allowFrozen || (!frozen && !runtimeOwned)) { "Runtime may still be running; use Restore to stop it safely" }
            }
            block()
        } finally { synchronized(monitor) { excluded = false; publishActive() } }
    }
}
