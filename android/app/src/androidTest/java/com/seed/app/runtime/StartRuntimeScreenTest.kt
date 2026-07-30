package com.seed.app.runtime

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.seed.app.R
import com.seed.app.ui.theme.SeedTheme
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartRuntimeScreenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun unknownShowsStartupTitleAndProgressWithoutRetry() {
        val startupTitle = composeRule.activity.getString(R.string.runtime_start_title)
        composeRule.setContent {
            SeedTheme {
                StartRuntimeScreen(
                    health = HealthState.Unknown,
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("runtime-start-screen").assertIsDisplayed()
        composeRule.onNodeWithText(startupTitle).assertIsDisplayed()
        composeRule.onNodeWithTag("runtime-start-progress").assertIsDisplayed()
        composeRule.onAllNodesWithTag("runtime-start-retry").assertCountEquals(0)
    }

    @Test
    fun pollingShowsStartupTitleProgressAndAttemptWithoutRetry() {
        val startupTitle = composeRule.activity.getString(R.string.runtime_start_title)
        val pollingStatus = composeRule.activity.getString(R.string.runtime_start_polling, 7)
        composeRule.setContent {
            SeedTheme {
                StartRuntimeScreen(
                    health = HealthState.Polling(attempt = 7),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText(startupTitle).assertIsDisplayed()
        composeRule.onNodeWithTag("runtime-start-progress").assertIsDisplayed()
        composeRule.onNodeWithText(pollingStatus).assertIsDisplayed()
        composeRule.onAllNodesWithTag("runtime-start-retry").assertCountEquals(0)
    }

    @Test
    fun healthyUsesWaitingProgressFallbackWithoutRetry() {
        val waitingStatus = composeRule.activity.getString(R.string.runtime_start_waiting)
        composeRule.setContent {
            SeedTheme {
                StartRuntimeScreen(
                    health = HealthState.Healthy(flask = "up"),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("runtime-start-progress").assertIsDisplayed()
        composeRule.onNodeWithText(waitingStatus).assertIsDisplayed()
        composeRule.onAllNodesWithTag("runtime-start-retry").assertCountEquals(0)
    }

    @Test
    fun unhealthyShowsAssertiveSuppliedMessageAndRetry() {
        val failureMessage = "Backend did not start"
        val retryLabel = composeRule.activity.getString(R.string.runtime_start_retry)
        composeRule.setContent {
            SeedTheme {
                StartRuntimeScreen(
                    health = HealthState.Unhealthy(failureMessage),
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithTag("runtime-start-error")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        composeRule.onNodeWithText(failureMessage).assertIsDisplayed()
        composeRule.onNodeWithTag("runtime-start-retry").assertIsDisplayed()
        composeRule.onNodeWithText(retryLabel).assertIsDisplayed()
        composeRule.onAllNodesWithTag("runtime-start-progress").assertCountEquals(0)
    }

    @Test
    fun clickingRetryInvokesCallbackExactlyOnce() {
        val failureMessage = "Backend did not start"
        val retryCount = AtomicInteger(0)
        composeRule.setContent {
            SeedTheme {
                StartRuntimeScreen(
                    health = HealthState.Unhealthy(failureMessage),
                    onRetry = { retryCount.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag("runtime-start-retry").performClick()

        composeRule.runOnIdle {
            assertEquals(1, retryCount.get())
        }
    }
}
