package com.seed.app.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.seed.app.R

/** Blocks navigation while the embedded runtime starts or waits for a retry. */
@Composable
fun StartRuntimeScreen(
    health: HealthState,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .testTag("runtime-start-screen"),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.runtime_start_title),
                    style = MaterialTheme.typography.titleLarge,
                )

                when (health) {
                    HealthState.Unknown -> RuntimeStartProgress(attempt = null)
                    is HealthState.Polling -> RuntimeStartProgress(attempt = health.attempt)
                    is HealthState.Healthy -> RuntimeStartProgress(attempt = null)
                    is HealthState.Unhealthy -> RuntimeStartError(
                        message = health.message,
                        onRetry = onRetry,
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeStartProgress(attempt: Int?) {
    CircularProgressIndicator(
        modifier = Modifier.testTag("runtime-start-progress"),
    )
    Text(
        text = if (attempt == null) {
            stringResource(R.string.runtime_start_waiting)
        } else {
            stringResource(R.string.runtime_start_polling, attempt)
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun RuntimeStartError(
    message: String,
    onRetry: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = LiveRegionMode.Assertive }
            .testTag("runtime-start-error"),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.runtime_start_error_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    Button(
        onClick = onRetry,
        modifier = Modifier.testTag("runtime-start-retry"),
    ) {
        Text(stringResource(R.string.runtime_start_retry))
    }
}
