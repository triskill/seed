package cz.trety.seed.device

/** Owner-thread gate: one outstanding delivery, exact sequence ACK, bounded waiting. */
internal class NativeSensorAckGate {
    private var sequence = 0L
    private var outstanding: Long? = null
    private var sentAt = 0L
    fun ready() = outstanding == null
    fun send(now: Long): Long? {
        if (!ready()) return null
        sequence++
        outstanding = sequence
        sentAt = now
        return sequence
    }
    fun acknowledge(value: Long) { if (value == outstanding) outstanding = null }
    fun timedOut(now: Long) = outstanding != null && now - sentAt >= 10_000_000_000L
}
