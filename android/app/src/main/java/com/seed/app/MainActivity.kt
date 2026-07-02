package com.seed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import com.seed.app.ui.nav.SeedNav

/**
 * Single-Activity entry point for Seed v0.1.
 *
 * Phase 5.1 shipped a placeholder body that rendered a
 * centred "Seed" Text. Phase 5.2 (4-section navigation)
 * replaces it with the real `SeedNav` host — a `Scaffold`
 * that owns the bottom `NavigationBar` and a `NavHost`
 * with four destinations. Phases 5.3–5.9 will fill in
 * each screen.
 *
 * `enableEdgeToEdge()` opts into the modern Android
 * 15+ edge-to-edge default (transparent system bars);
 * the Scaffold's `bottomBar` handles the safe area for
 * the navigation bar.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SeedNav()
            }
        }
    }
}
