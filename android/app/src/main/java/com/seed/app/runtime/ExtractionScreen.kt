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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.seed.app.R

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
                text = stringResource(R.string.extraction_screen_title),
                style = MaterialTheme.typography.titleLarge,
            )
            when (val s = state) {
                BootState.NeedsExtraction -> CircularProgressIndicator()
                is BootState.Extracting -> when (val p = s.progress) {
                    is ExtractionProgress.Started -> {
                        CircularProgressIndicator()
                        Text(
                            stringResource(
                                R.string.extraction_screen_started,
                                p.fileCount,
                                p.totalBytes / 1024,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    is ExtractionProgress.FileProgress -> {
                        LinearProgressIndicator(
                            progress = { (p.bytesDone.toFloat() / p.totalBytes).coerceIn(0f, 1f) },
                        )
                        Text(
                            friendlyAssetName(p.name),
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

/**
 * Map the technical asset filename to a user-friendly label.
 * Falls back to the raw filename for assets that don't need a label.
 *
 * Names match what [AndroidAssetSource.entries] returns, which
 * are the merged-asset names inside the APK — not the source
 * filenames in `assets/linux/`. The merged name for the rootfs
 * is `rootfs.tar` (the source's `.gz` is stripped by the AGP
 * CompressAssetsTask; see `app/build.gradle.kts` for why).
 */
@Composable
private fun friendlyAssetName(name: String): String = when (name) {
    "proot" -> stringResource(R.string.extraction_asset_proot)
    "rootfs.tar" -> stringResource(R.string.extraction_asset_rootfs)
    "seed_version.json" -> stringResource(R.string.extraction_asset_version)
    else -> name
}
