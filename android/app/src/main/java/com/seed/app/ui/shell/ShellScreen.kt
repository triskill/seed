package com.seed.app.ui.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
 * Shell tab — interactive PTY-backed shell into the
 * Seed runtime.
 *
 * Phase 5.2 ships a placeholder. Phase 5.5 (Shell UI)
 * replaces it with an input row, a monospaced output
 * `LazyColumn`, and a cancel button. The actual
 * `POST /shell/exec` call lands in Phase 6.1 with the
 * Retrofit client.
 */
@Composable
fun ShellScreen(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.shell_screen_title),
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = stringResource(R.string.shell_screen_subtitle),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Preview(showBackground = true, name = "Shell tab placeholder")
@Composable
private fun ShellScreenPreview() {
    MaterialTheme {
        ShellScreen()
    }
}
