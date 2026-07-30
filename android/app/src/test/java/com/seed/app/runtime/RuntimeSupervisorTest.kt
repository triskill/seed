package com.seed.app.runtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSupervisorTest {

    @Test
    fun firstStartStartsOneProcessAndRepublishesHealth() = runTest {
        val handle = FakeProotHandle()
        var processStarts = 0
        var probeStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                processStarts += 1
                handle
            },
            healthStates = {
                probeStarts += 1
                flowOf(HealthState.Polling(1), HealthState.Healthy("up"))
            },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals(1, processStarts)
        assertEquals(1, probeStarts)
        assertEquals(HealthState.Healthy("up"), supervisor.health.value)
        assertTrue(supervisor.isRuntimeAlive)
    }

    @Test
    fun retryWhileProcessIsAliveReusesItAndStartsFreshProbe() = runTest {
        val handle = FakeProotHandle()
        var processStarts = 0
        var probeStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                processStarts += 1
                handle
            },
            healthStates = {
                probeStarts += 1
                if (probeStarts == 1) {
                    flow<HealthState> {
                        emit(HealthState.Polling(1))
                        awaitCancellation()
                    }
                } else {
                    flowOf<HealthState>(HealthState.Healthy("recovered"))
                }
            },
        )

        supervisor.startOrRetry()
        runCurrent()
        assertEquals(HealthState.Polling(1), supervisor.health.value)

        supervisor.startOrRetry()
        assertEquals(HealthState.Unknown, supervisor.health.value)
        runCurrent()

        assertEquals(1, processStarts)
        assertEquals(2, probeStarts)
        assertEquals(HealthState.Healthy("recovered"), supervisor.health.value)
    }

    @Test
    fun retryAfterProcessDeathDestroysStaleHandleAndStartsNewProcess() = runTest {
        val stale = FakeProotHandle()
        val replacement = FakeProotHandle()
        val handles = ArrayDeque(listOf(stale, replacement))
        var processStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                processStarts += 1
                handles.removeFirst()
            },
            healthStates = { flowOf(HealthState.Healthy("up")) },
        )

        supervisor.startOrRetry()
        runCurrent()
        stale.alive = false

        supervisor.startOrRetry()
        runCurrent()

        assertEquals(2, processStarts)
        assertEquals(1, stale.destroyCalls)
        assertEquals(0, replacement.destroyCalls)
        assertTrue(supervisor.isRuntimeAlive)
    }

    @Test
    fun processStartExceptionBecomesUnhealthy() = runTest {
        var probeStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { throw IllegalStateException("proot missing") },
            healthStates = {
                probeStarts += 1
                emptyFlow()
            },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals(HealthState.Unhealthy("proot missing"), supervisor.health.value)
        assertEquals(0, probeStarts)
        assertFalse(supervisor.isRuntimeAlive)
    }

    @Test
    fun processStartExceptionIsReportedWithItsStackTrace() = runTest {
        val expectedFailure = IllegalStateException("proot missing")
        var reportedMessage: String? = null
        var reportedFailure: Throwable? = null
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { throw expectedFailure },
            healthStates = { emptyFlow() },
            onFailure = { message, failure ->
                reportedMessage = message
                reportedFailure = failure
            },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals("Could not start embedded runtime", reportedMessage)
        assertSame(expectedFailure, reportedFailure)
    }

    @Test
    fun unexpectedHealthExceptionIsReportedWithItsStackTrace() = runTest {
        val expectedFailure = IllegalStateException("probe crashed")
        var reportedMessage: String? = null
        var reportedFailure: Throwable? = null
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { FakeProotHandle() },
            healthStates = {
                flow {
                    throw expectedFailure
                }
            },
            onFailure = { message, failure ->
                reportedMessage = message
                reportedFailure = failure
            },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals("Health check failed", reportedMessage)
        assertSame(expectedFailure, reportedFailure)
    }

    @Test
    fun unexpectedHealthFlowExceptionBecomesUnhealthy() = runTest {
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { FakeProotHandle() },
            healthStates = {
                flow {
                    emit(HealthState.Polling(1))
                    throw IllegalStateException("probe crashed")
                }
            },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals(HealthState.Unhealthy("probe crashed"), supervisor.health.value)
    }

    @Test
    fun healthFlowCancellationIsNotTranslatedToUnhealthy() = runTest {
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { FakeProotHandle() },
            healthStates = {
                flow {
                    emit(HealthState.Polling(1))
                    throw CancellationException("probe cancelled")
                }
            },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals(HealthState.Polling(1), supervisor.health.value)
    }

    @Test
    fun cancelledHealthCollectorCannotPublishAfterRetry() = runTest {
        val oldCollectorCancelled = CompletableDeferred<Unit>()
        val releaseOldCollector = CompletableDeferred<Unit>()
        var probeStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { FakeProotHandle() },
            healthStates = {
                probeStarts += 1
                if (probeStarts == 1) {
                    flow {
                        try {
                            emit(HealthState.Polling(1))
                            awaitCancellation()
                        } finally {
                            oldCollectorCancelled.complete(Unit)
                            withContext(NonCancellable) { releaseOldCollector.await() }
                            throw IllegalStateException("stale collector")
                        }
                    }
                } else {
                    flowOf(HealthState.Healthy("new generation"))
                }
            },
        )

        supervisor.startOrRetry()
        runCurrent()
        supervisor.startOrRetry()
        runCurrent()

        assertTrue(oldCollectorCancelled.isCompleted)
        releaseOldCollector.complete(Unit)
        runCurrent()

        assertEquals(2, probeStarts)
        assertEquals(HealthState.Healthy("new generation"), supervisor.health.value)
    }

    @Test
    fun concurrentRetriesAreSerializedWithoutDuplicateProcesses() = runTest {
        val releaseCallers = CountDownLatch(1)
        val callersDone = CountDownLatch(2)
        val secondStartEntered = CountDownLatch(1)
        val processStarts = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                val startNumber = processStarts.incrementAndGet()
                if (startNumber == 1 && callersDone.count > 0) {
                    check(secondStartEntered.await(2, TimeUnit.SECONDS)) {
                        "concurrent retry did not enter process creation"
                    }
                } else if (startNumber == 2) {
                    secondStartEntered.countDown()
                }
                FakeProotHandle()
            },
            healthStates = { emptyFlow() },
        )

        try {
            val calls = List(2) {
                executor.submit {
                    releaseCallers.await()
                    try {
                        supervisor.startOrRetry()
                    } finally {
                        callersDone.countDown()
                    }
                }
            }
            releaseCallers.countDown()
            calls.forEach { it.get(3, TimeUnit.SECONDS) }
            runCurrent()

            assertEquals(1, processStarts.get())
        } finally {
            supervisor.stop()
            executor.shutdownNow()
        }
    }

    @Test
    fun startOrRetryQueuesProcessCreationInSuppliedScope() = runTest {
        var processStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                processStarts += 1
                FakeProotHandle()
            },
            healthStates = { emptyFlow() },
        )

        supervisor.startOrRetry()

        assertEquals(0, processStarts)
        runCurrent()
        assertEquals(1, processStarts)
    }

    @Test
    fun retryAfterStopNeverStartsProcess() = runTest {
        var processStarts = 0
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                processStarts += 1
                FakeProotHandle()
            },
            healthStates = { emptyFlow() },
        )

        supervisor.stop()
        supervisor.startOrRetry()
        runCurrent()

        assertEquals(0, processStarts)
        assertFalse(supervisor.isRuntimeAlive)
    }

    @Test
    fun processCreatedWhileStopIsInFlightIsDestroyedInsteadOfInstalled() = runTest {
        val createdHandle = FakeProotHandle()
        lateinit var supervisor: RuntimeSupervisor
        supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = {
                supervisor.stop()
                createdHandle
            },
            healthStates = { emptyFlow() },
        )

        supervisor.startOrRetry()
        runCurrent()

        assertEquals(1, createdHandle.destroyCalls)
        assertFalse(createdHandle.isAlive)
        assertFalse(supervisor.isRuntimeAlive)
    }

    @Test
    fun stopCancelsPollingAndDestroysActiveHandleExactlyOnce() = runTest {
        val handle = FakeProotHandle()
        var pollingCancelled = false
        val supervisor = RuntimeSupervisor(
            scope = backgroundScope,
            startProcess = { handle },
            healthStates = {
                flow {
                    emit(HealthState.Polling(1))
                    try {
                        awaitCancellation()
                    } finally {
                        pollingCancelled = true
                    }
                }
            },
        )
        supervisor.startOrRetry()
        runCurrent()

        supervisor.stop()
        supervisor.stop()
        runCurrent()

        assertTrue(pollingCancelled)
        assertEquals(1, handle.destroyCalls)
        assertFalse(supervisor.isRuntimeAlive)
    }
}

private class FakeProotHandle(
    var alive: Boolean = true,
) : ProotHandle {
    var destroyCalls: Int = 0
        private set

    override val isAlive: Boolean get() = alive
    override val stdout: Flow<String> = emptyFlow()
    override val stderr: Flow<String> = emptyFlow()

    override fun destroy() {
        destroyCalls += 1
        alive = false
    }
}
