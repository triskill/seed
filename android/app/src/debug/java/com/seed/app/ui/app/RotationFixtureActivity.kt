package com.seed.app.ui.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Isolated instrumentation host. Never included in release or exported. */
class RotationFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content(savedInstanceState != null) }
    }

    companion object {
        var content: @Composable (Boolean) -> Unit = {}
    }
}
