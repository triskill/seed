package cz.trety.seed.ui.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

/** Keep navigation's unconsumed saved state alive while runtime binding delays its composition. */
@Composable
internal fun RetainedAppNavigation(
    ready: Boolean,
    waiting: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    // Always composed in the same root slot, including extraction/binding/error screens.
    val navigationState = rememberSaveableStateHolder()
    if (ready) navigationState.SaveableStateProvider("seed-navigation", content)
    else waiting()
}
