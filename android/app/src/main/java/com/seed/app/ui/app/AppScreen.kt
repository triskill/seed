package com.seed.app.ui.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.seed.app.R

/**
 * App tab — shows the user's webapp inside a WebView.
 *
 * Phase 5.2 ships a placeholder so the bottom-nav
 * skeleton can be wired up. Phase 5.3 (WebView) replaces
 * the body with an `AndroidView` wrapping a `WebView`
 * that loads `BuildConfig.WEBAPP_DEV_URL`.
 *
 * The placeholder is intentionally a single Column
 * (not a Composable that takes a `padding` from the
 * parent Scaffold) — `SeedNav` already applies the
 * inner padding to the NavHost, so each screen body
 * only needs to fill the available space.
 */
@Composable
fun AppScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.app_screen_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.app_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true, name = "App tab placeholder")
@Composable
private fun AppScreenPreview() {
    MaterialTheme {
        AppScreen()
    }
}
