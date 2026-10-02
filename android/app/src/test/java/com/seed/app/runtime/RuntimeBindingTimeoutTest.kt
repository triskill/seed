package com.seed.app.runtime

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeBindingTimeoutTest {
    @Test
    fun acceptedBindingExpiresOnceAfterTenSeconds() = runTest {
        var failures = 0
        val timeout = RuntimeBindingTimeout(backgroundScope) { failures++ }
        timeout.arm()
        runCurrent()
        advanceTimeBy(9_999)
        runCurrent()
        assertEquals(0, failures)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, failures)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, failures)
    }

    @Test
    fun connectedOrReleasedBindingCancelsDeadline() = runTest {
        var failures = 0
        val timeout = RuntimeBindingTimeout(backgroundScope) { failures++ }
        timeout.arm()
        runCurrent()
        advanceTimeBy(5_000)
        timeout.cancel()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, failures)
    }

    @Test
    fun retryGetsItsOwnDeadlineAndOldAttemptCannotExpireIt() = runTest {
        var failures = 0
        val timeout = RuntimeBindingTimeout(backgroundScope) { failures++ }
        timeout.arm()
        runCurrent()
        advanceTimeBy(5_000)
        timeout.arm()
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(0, failures)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, failures)
    }

    @Test
    fun destroyingOwnerCancelsPendingDeadline() = runTest {
        var failures = 0
        val job = kotlinx.coroutines.Job()
        val scope = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + job)
        val timeout = RuntimeBindingTimeout(scope) { failures++ }
        timeout.arm()
        runCurrent()
        job.cancel()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(0, failures)
    }
}
