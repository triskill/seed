package cz.trety.seed.device

import org.junit.Assert.*
import org.junit.Test

class NativeSensorSamplerTest {
    @Test fun latestOverwriteAndNoBacklog() {
        val sampler = NativeSensorSampler(30)
        sampler.offer(mapOf("n" to 1))
        sampler.offer(mapOf("n" to 2))
        assertEquals(2, sampler.take(0)?.get("n"))
        sampler.offer(mapOf("n" to 3))
        assertNull(sampler.take(10_000_000))
        assertEquals(3, sampler.take(1_000_000_000)?.get("n"))
        assertNull(sampler.take(2_000_000_000))
    }
    @Test fun capsThirtyAndSixtyWithoutCatchup() {
        for (rate in listOf(30, 60)) {
            val sampler = NativeSensorSampler(rate)
            var count = 0
            for (time in 0L until 1_000_000_000L step 1_000_000L) {
                sampler.offer(emptyMap())
                if (sampler.take(time) != null) count++
            }
            assertTrue(count <= rate)
        }
    }
    @Test fun scheduledPumpDoesNotAccidentallyHalveRequestedRate() {
        for (rate in listOf(30, 60)) {
            val sampler = NativeSensorSampler(rate)
            var delivered = 0
            for (millis in 0L until 1000L step sampler.deliveryDelayMillis) {
                sampler.offer(emptyMap())
                if (sampler.take(millis * 1_000_000L) != null) delivered++
            }
            assertTrue("$rate Hz pump delivered only $delivered", delivered >= rate - 2)
            assertTrue(delivered <= rate)
        }
    }
    @Test fun oneUnackedSampleWrongAckIgnoredAndTimeout() {
        val gate = NativeSensorAckGate()
        assertEquals(1L, gate.send(0))
        assertNull(gate.send(1))
        gate.acknowledge(2)
        assertNull(gate.send(2))
        assertTrue(gate.timedOut(10_000_000_000))
        gate.acknowledge(1)
        assertEquals(2L, gate.send(11_000_000_000))
        gate.acknowledge(1)
        assertNull(gate.send(12_000_000_000))
    }
    @Test fun subscribeSharesSensorsConsent() {
        assertEquals(DeviceConsentGroup.SENSORS, deviceConsentGroup("sensor.subscribe"))
    }
}
