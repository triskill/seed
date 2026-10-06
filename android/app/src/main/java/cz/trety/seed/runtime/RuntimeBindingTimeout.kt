package cz.trety.seed.runtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread-owned deadline for an accepted, not-yet-connected binding. */
internal class RuntimeBindingTimeout(
    private val scope: CoroutineScope,
    private val onTimeout: () -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L

    fun arm() {
        cancel()
        val attempt = generation
        job = scope.launch {
            delay(10_000)
            if (generation == attempt) {
                job = null
                onTimeout()
            }
        }
    }

    fun cancel() {
        generation++
        job?.cancel()
        job = null
    }
}
