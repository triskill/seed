package com.seed.app.device

import org.junit.Assert.*
import org.junit.Test

class CameraResultSlotTest {
    @Test fun cancelledCallerKeepsSlotUntilLateResult() {
        val slot = CameraResultSlot<String>()
        var result: String? = null
        assertTrue(slot.reserve { result = it })
        slot.abandon()
        assertFalse(slot.reserve { result = it })
        slot.complete("old")
        assertNull(result)
        assertTrue(slot.reserve { result = it })
        slot.complete("new")
        assertEquals("new", result)
    }
    @Test fun oldCancellationCannotAbandonNewReservation() {
        val slot = CameraResultSlot<String>()
        slot.reserve {}
        val old = slot.token()
        slot.complete("old")
        var result: String? = null
        slot.reserve { result = it }
        slot.abandon(old)
        slot.complete("new")
        assertEquals("new", result)
    }
    @Test fun closeDiscardsCallbacksAndRejectsNewCalls() {
        val slot = CameraResultSlot<String>()
        assertTrue(slot.reserve { fail("callback after close") })
        slot.close()
        slot.complete("late")
        assertFalse(slot.reserve {})
    }
}
