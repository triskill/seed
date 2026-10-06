package cz.trety.seed.runtime

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import cz.trety.seed.ui.settings.RuntimeRestoreSettings
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RuntimeRestoreSettingsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun confirmationRequiredAndCancelDoesNotRestore() {
        var restores = 0
        compose.setContent { MaterialTheme { RuntimeRestoreSettings(RestoreState.Idle) { restores++ } } }
        compose.onNodeWithText("Restore Linux runtime").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(0, restores)
        compose.onNodeWithText("Restore Linux runtime").performClick()
        compose.onNodeWithText("Restore", useUnmergedTree = true).performClick()
        assertEquals(1, restores)
    }
    @Test fun progressDisablesDuplicateRequestsWithoutBackend() {
        compose.setContent { MaterialTheme { RuntimeRestoreSettings(RestoreState.Working("Stopping runtime…")) {} } }
        compose.onNodeWithText("Restore Linux runtime").assertIsNotEnabled()
        compose.onNodeWithText("Stopping runtime…").assertExists()
    }
    @Test fun failureExposesNativeRetry() {
        compose.setContent { MaterialTheme { RuntimeRestoreSettings(RestoreState.Failed("Exit not confirmed")) {} } }
        compose.onNodeWithText("Exit not confirmed").assertExists()
        compose.onNodeWithText("Retry Restore").performClick()
        compose.onNodeWithText("Restore Linux runtime?").assertExists()
    }
}
