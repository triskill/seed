package com.seed.app.runtime

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Full-screen "preparing runtime" UI shown while [BootController]
 * is in [BootState.NeedsExtraction] or [BootState.Extracting].
 *
 * The screen has no buttons (no cancel in v0.1) and no navigation
 * — it blocks the rest of the app until the runtime is on disk.
 * Phase 9's [SetupScreen] will replace this once it has real user-
 * facing choices (API key, provider) to offer before the
 * extraction kicks off.
 */
@Composable
fun ExtractionScreen(state: BootState) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "Preparing Seed runtime",
                style = MaterialTheme.typography.titleLarge,
            )
            when (val s = state) {
                BootState.NeedsExtraction -> CircularProgressIndicator()
                is BootState.Extracting -> when (val p = s.progress) {
                    is ExtractionProgress.Started -> {
                        CircularProgressIndicator()
                        Text(
                            "${p.fileCount} files, ${p.totalBytes / 1024} KiB",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is ExtractionProgress.FileProgress -> {
                        LinearProgressIndicator(
                            progress = { (p.bytesDone.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                        )
                        Text(
                            p.name,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    ExtractionProgress.Finished -> CircularProgressIndicator()
                }
                BootState.Ready -> {
                    // Parent should not be rendering this screen in Ready state;
                    // we still draw a spinner to avoid a blank frame.
                    CircularProgressIndicator()
                }
            }
        }
    }
}
