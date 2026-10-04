package com.seed.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import com.seed.app.runtime.RestoreState

/** Native-only maintenance surface; never depends on backend HTTP or Python. */
@Composable
fun RuntimeRestoreSettings(state: RestoreState, onRestore: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Column {
        Button(onClick = { confirm = true }, enabled = state !is RestoreState.Working) {
            Text(if (state is RestoreState.Failed) "Retry Restore" else "Restore Linux runtime")
        }
        when (state) {
            is RestoreState.Working -> Text(state.message)
            is RestoreState.Failed -> Text(state.message)
            RestoreState.Finished -> Text("Linux runtime restored. Starting runtime…")
            RestoreState.Idle -> Unit
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Restore Linux runtime?") },
        text = { Text("Reinstall Linux from this APK. Your generated app, backend configuration, known Pi credentials and Android app data are preserved. All other Linux files are discarded. Running tasks and shell commands are stopped and will not be replayed. This is not a permanent backup.") },
        confirmButton = { TextButton(onClick = { confirm = false; onRestore() }) { Text("Restore") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
