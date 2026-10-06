package cz.trety.seed.device

/** Main-thread-owned slot. Abandoning a caller never releases an external activity's slot. */
internal class CameraResultSlot<T> {
    private var generation = 0L
    private var occupied = false
    private var closed = false
    private var callback: ((T) -> Unit)? = null
    fun reserve(callback: (T) -> Unit): Boolean {
        if (occupied || closed) return false
        generation++
        occupied = true
        this.callback = callback
        return true
    }
    fun token(): Long = generation
    fun abandon(token: Long = generation) { if (token == generation) callback = null }
    /** Returns whether a live caller consumed the result. */
    fun complete(value: T): Boolean {
        val consumer = callback
        callback = null
        occupied = false
        consumer?.invoke(value)
        return consumer != null
    }
    fun close() { closed = true; callback = null }
    fun isBusy(): Boolean = occupied
}
