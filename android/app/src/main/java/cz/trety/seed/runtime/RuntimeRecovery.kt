package cz.trety.seed.runtime

/** End the old shell session before replacing the backend; never delete runtime data. */
internal fun restartRuntimeWithFreshTerminal(
    closeTerminal: () -> Unit,
    restartRuntime: () -> Unit,
) {
    closeTerminal()
    restartRuntime()
}
