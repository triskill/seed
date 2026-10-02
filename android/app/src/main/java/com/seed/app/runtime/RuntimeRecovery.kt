package com.seed.app.runtime

/** End the old shell session before replacing the backend; never delete runtime data. */
internal fun restartRuntimeWithFreshTerminal(
    closeTerminal: () -> Unit,
    restartRuntime: () -> Unit,
) {
    closeTerminal()
    restartRuntime()
}
