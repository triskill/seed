package com.seed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.seed.app.ui.nav.SeedNav
import com.seed.app.ui.theme.SeedTheme

/**
 * Single-Activity entry point for Seed v0.1.
 *
 * Phase 5.1 shipped a placeholder body that rendered a
 * centred "Seed" Text. Phase 5.2 (4-section navigation)
 * replaced it with the real `SeedNav` host — a `Scaffold`
 * that owns the bottom `NavigationBar` and a `NavHost`
 * with four destinations. Phases 5.3–5.7 filled in
 * each screen.
 *
 * **Phase 5.8** wraps the content in [SeedTheme]
 * (was bare `MaterialTheme {}` from 5.2–5.7).
 * `SeedTheme` provides the green primary palette,
 * the light/dark switch, and the `SideEffect` that
 * tints the system bars to match the active scheme.
 *
 * `enableEdgeToEdge()` opts into the modern Android
 * 15+ edge-to-edge default (transparent system bars);
 * the Scaffold's `bottomBar` handles the safe area for
 * the navigation bar, and the `SideEffect` in
 * `SeedTheme` keeps the bar icons legible on the
 * translucent surface.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SeedTheme {
                SeedNav()
            }
        }
    }
}
