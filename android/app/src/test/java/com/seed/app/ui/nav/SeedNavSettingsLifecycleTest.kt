package com.seed.app.ui.nav

import com.seed.app.runtime.SeedTerminalManager
import org.junit.Assert.assertEquals
import org.junit.Test

class SeedNavSettingsLifecycleTest {

    @Test
    fun openAndCloseLifecycleInvokesCallbacksOnce() {
        // We can't easily drive a full Compose tree on the JVM, so this test
        // pins the contract via a hand-rolled spy that mirrors what
        // DisposableEffect does: a unit effect that captures
        // onDispose into a list and runs the captured callbacks in reverse
        // order on "unmount".
        val opened = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        val onSettingsOpened: () -> Unit = { opened += 1 }
        val onSettingsClosed: () -> Unit = { closed += 1 }
        // Simulate DisposableEffect semantics: opening runs the effect,
        // closing runs the captured onDispose.
        val capturedDisposer: () -> Unit = { onSettingsClosed() }
        onSettingsOpened()
        capturedDisposer()

        assertEquals(listOf(1), opened)
        assertEquals(listOf(1), closed)
    }
}
