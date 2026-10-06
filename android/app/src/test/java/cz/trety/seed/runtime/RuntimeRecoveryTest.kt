package cz.trety.seed.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeRecoveryTest {
    @Test
    fun explicitRestartClosesTerminalBeforeReplacingRuntime() {
        val actions = mutableListOf<String>()
        restartRuntimeWithFreshTerminal(
            closeTerminal = { actions += "close terminal" },
            restartRuntime = { actions += "restart runtime" },
        )
        assertEquals(listOf("close terminal", "restart runtime"), actions)
    }

    @Test
    fun failedTerminalCloseDoesNotStartOverlappingRecovery() {
        var restarts = 0
        assertThrows(IllegalStateException::class.java) {
            restartRuntimeWithFreshTerminal(
                closeTerminal = { error("close failed") },
                restartRuntime = { restarts++ },
            )
        }
        assertEquals(0, restarts)
    }
}
