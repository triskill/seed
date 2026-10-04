package com.seed.app.device

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

internal interface LocationForeground {
    fun isVisible(): Boolean
    suspend fun awaitResumed()
    fun onStop(callback: () -> Unit): () -> Unit
}

internal object AlwaysLocationForeground : LocationForeground {
    override fun isVisible() = true
    override suspend fun awaitResumed() = Unit
    override fun onStop(callback: () -> Unit): () -> Unit = {}
}

/** Only location acquisition is gated; external camera/permission result slots are not cancelled on pause. */
internal class LifecycleLocationForeground(private val lifecycle: Lifecycle) : LocationForeground {
    private val state = MutableStateFlow(lifecycle.currentState)
    private val stopCallbacks = mutableSetOf<() -> Unit>()
    private val observer = LifecycleEventObserver { _, event ->
        state.value = lifecycle.currentState
        if (event == Lifecycle.Event.ON_STOP) stopCallbacks.toList().forEach { it() }
    }
    fun attach() { lifecycle.addObserver(observer); state.value = lifecycle.currentState }
    fun detach() { lifecycle.removeObserver(observer); stopCallbacks.clear() }
    override fun isVisible() = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    override suspend fun awaitResumed() { state.first { it.isAtLeast(Lifecycle.State.RESUMED) } }
    override fun onStop(callback: () -> Unit): () -> Unit { stopCallbacks.add(callback); return { stopCallbacks.remove(callback) } }
}
