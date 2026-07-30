package com.seed.app.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeStartupTest {

    @Test
    fun extractionStatesNeverTriggerRuntimeStartupGate() {
        var readyCalls = 0
        val gate = RuntimeStartupGate { readyCalls += 1 }

        gate.update(BootState.NeedsExtraction)
        gate.update(BootState.Extracting(ExtractionProgress.Started(totalBytes = 10, fileCount = 1)))
        gate.update(BootState.Extracting(ExtractionProgress.Finished))

        assertEquals(0, readyCalls)
    }

    @Test
    fun firstReadyTriggersRuntimeStartupGateOnce() {
        var readyCalls = 0
        val gate = RuntimeStartupGate { readyCalls += 1 }

        gate.update(BootState.Ready)

        assertEquals(1, readyCalls)
    }

    @Test
    fun repeatedReadyAndLaterStateOscillationDoNotTriggerGateAgain() {
        var readyCalls = 0
        val gate = RuntimeStartupGate { readyCalls += 1 }

        gate.update(BootState.Ready)
        gate.update(BootState.Ready)
        gate.update(BootState.NeedsExtraction)
        gate.update(BootState.Extracting(ExtractionProgress.Finished))
        gate.update(BootState.Ready)

        assertEquals(1, readyCalls)
    }

    @Test
    fun extractionDestinationWinsRegardlessOfStaleHealth() {
        val extracting = BootState.Extracting(
            ExtractionProgress.FileProgress(
                name = "rootfs.tar",
                bytesDone = 5,
                totalBytes = 10,
            ),
        )

        assertEquals(
            StartupDestination.Extraction(BootState.NeedsExtraction),
            resolveStartupDestination(BootState.NeedsExtraction, HealthState.Healthy("up")),
        )
        assertEquals(
            StartupDestination.Extraction(extracting),
            resolveStartupDestination(extracting, HealthState.Unhealthy("stale failure")),
        )
    }

    @Test
    fun readyWithUnknownPollingOrUnhealthyMapsToRuntime() {
        val startupHealth = listOf(
            HealthState.Unknown,
            HealthState.Polling(3),
            HealthState.Unhealthy("not ready"),
        )

        startupHealth.forEach { health ->
            assertEquals(
                StartupDestination.Runtime(health),
                resolveStartupDestination(BootState.Ready, health),
            )
        }
    }

    @Test
    fun onlyReadyWithHealthyMapsToSeed() {
        assertEquals(
            StartupDestination.Seed,
            resolveStartupDestination(BootState.Ready, HealthState.Healthy("down")),
        )
        assertEquals(
            StartupDestination.Extraction(BootState.NeedsExtraction),
            resolveStartupDestination(BootState.NeedsExtraction, HealthState.Healthy("up")),
        )
    }

    @Test
    fun notificationPermissionPolicyIsTrueOnlyForApi33PlusUngrantedNeverRequested() {
        assertFalse(
            shouldRequestRuntimeNotificationPermission(
                sdkInt = 32,
                granted = false,
                alreadyRequested = false,
            ),
        )
        assertFalse(
            shouldRequestRuntimeNotificationPermission(
                sdkInt = 33,
                granted = true,
                alreadyRequested = false,
            ),
        )
        assertFalse(
            shouldRequestRuntimeNotificationPermission(
                sdkInt = 33,
                granted = false,
                alreadyRequested = true,
            ),
        )
        assertTrue(
            shouldRequestRuntimeNotificationPermission(
                sdkInt = 33,
                granted = false,
                alreadyRequested = false,
            ),
        )
        assertTrue(
            shouldRequestRuntimeNotificationPermission(
                sdkInt = 34,
                granted = false,
                alreadyRequested = false,
            ),
        )
    }
}
