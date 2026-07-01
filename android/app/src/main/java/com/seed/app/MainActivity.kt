package com.seed.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

/**
 * Single-Activity entry point for Seed v0.1.
 *
 * Phase 5.1 ships the minimum: a Compose surface that
 * renders "Seed" centred on screen. Tasks 5.2 (4-section
 * navigation) and 5.3 (WebView) replace this body with
 * the real `SeedNav` host. The `enableEdgeToEdge` call
 * opts into the modern Android 15+ edge-to-edge default
 * (so the system bars are transparent and our content
 * draws under them — the Scaffold's `contentWindowInsets`
 * handles the safe area).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                SeedPlaceholderScreen()
            }
        }
    }
}

@Composable
private fun SeedPlaceholderScreen() {
    Scaffold { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            color = MaterialTheme.colorScheme.background,
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Seed",
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
        }
    }
}

@Preview(showBackground = true, name = "Seed placeholder")
@Composable
private fun SeedPlaceholderPreview() {
    MaterialTheme {
        SeedPlaceholderScreen()
    }
}
