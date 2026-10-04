package com.seed.app.runtime

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeMaintenanceGateTest {
    @Test fun failedStopFreezeRejectsBootAndWritersUntilExplicitResume() = runBlocking {
        RuntimeMaintenanceGate.freezeWriters()
        try {
            assertThrows(IllegalStateException::class.java) { RuntimeMaintenanceGate.withWriter { 1 } }
            assertThrows(IllegalStateException::class.java) { runBlocking { RuntimeMaintenanceGate.exclusive { 1 } } }
            assertEquals(9, RuntimeMaintenanceGate.exclusive(allowFrozen = true) { 9 })
        } finally { RuntimeMaintenanceGate.resumeWriters() }
        assertEquals(1, RuntimeMaintenanceGate.withWriter { 1 })
    }

    @Test fun waitsForAdmittedWriterWithoutBlockingNewSynchronousCallers() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val writer = Thread { RuntimeMaintenanceGate.withWriter { entered.countDown(); release.await() } }
        writer.start()
        entered.await()
        try {
            assertThrows(IllegalStateException::class.java) { RuntimeMaintenanceGate.withWriter { Unit } }
            val maintenance = async { RuntimeMaintenanceGate.exclusive { 42 } }
            kotlinx.coroutines.delay(30)
            org.junit.Assert.assertFalse(maintenance.isCompleted)
            release.countDown()
            assertEquals(42, maintenance.await())
        } finally { release.countDown(); writer.join() }
    }

    @Test fun exclusiveRejectsWritersAndReleasesAfterFailure() = runBlocking {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { RuntimeMaintenanceGate.exclusive {
                RuntimeMaintenanceGate.withWriter { error("writer entered") }
            } }
        }
        assertEquals(7, RuntimeMaintenanceGate.withWriter { 7 })
    }
}
