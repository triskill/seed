package cz.trety.seed.device

/** Thread-safe single latest-value slot. Clock is native monotonic time, never sensor timestamps. */
internal class NativeSensorSampler(rateHz: Int) {
    private val interval = (1_000_000_000L + rateHz - 1) / rateHz
    // Round up: a 33ms pump paired with a 33.334ms gate skips every other tick.
    val deliveryDelayMillis = (interval + 999_999L) / 1_000_000L
    private var latest: Map<String, Any?>? = null
    private var next = Long.MIN_VALUE
    @Synchronized fun offer(sample: Map<String, Any?>) { latest = sample }
    @Synchronized fun take(now: Long): Map<String, Any?>? {
        if (now < next) return null
        val sample = latest ?: return null
        latest = null
        next = now + interval
        return sample
    }
    @Synchronized fun clear() { latest = null }
}
