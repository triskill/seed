package com.seed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.lifecycleScope
import com.seed.app.runtime.AndroidAssetSource
import com.seed.app.runtime.BootController
import com.seed.app.runtime.BootState
import com.seed.app.runtime.ExtractionScreen
import com.seed.app.runtime.RootfsVersion
import com.seed.app.ui.nav.SeedNav
import com.seed.app.ui.theme.SeedTheme
import java.io.File

/**
 * Single-Activity entry point for Seed v0.1.
 *
 * Phase 7.5 added the boot controller: on first launch, or whenever
 * the runtime version in the APK doesn't match the on-disk version,
 * we show a full-screen extraction progress UI until the runtime is
 * on disk, then proceed to the normal `SeedNav`. See
 * `com.seed.app.runtime` for the controller + screen + extractor.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // BootController owns the "is the runtime on disk and up to date?"
        // decision. The asset source wraps AssetManager; the asset version
        // is read once from the JSON file the build script dropped in.
        val targetDir = File(filesDir, "linux")
        val assetSource = AndroidAssetSource(assets)
        val assetVersion = assets.open("linux/seed_version.json").bufferedReader()
            .use { RootfsVersion.parse(it.readText()) }

        setContent {
            SeedTheme {
                // Pass lifecycleScope so the extraction is cancelled
                // when the activity is destroyed (e.g. "Don't keep
                // activities" dev option, memory pressure). Without
                // this, a recreated activity would race with the
                // still-running previous extraction and two writers
                // could corrupt the rootfs tarball.
                val boot = remember {
                    BootController(targetDir, assetSource, assetVersion, lifecycleScope)
                }
                val state by boot.states.collectAsState()
                LaunchedEffect(state) {
                    // Kick off extraction the first time we see NeedsExtraction.
                    if (state is BootState.NeedsExtraction) {
                        boot.runExtraction()
                    }
                }
                when (state) {
                    BootState.Ready -> SeedNav()
                    else -> ExtractionScreen(state)
                }
            }
        }
    }
}
