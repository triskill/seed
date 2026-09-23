package com.seed.app.runtime

import com.seed.app.ui.settings.SettingsForm

/** The backend Pi settings store owns provider, model, thinking and credentials. */
internal fun SettingsForm?.toPiRuntimeEnvironment(
    allowMissingModel: Boolean = false,
): Map<String, String> = mapOf("PI_CODING_AGENT_DIR" to "/home/seed/.pi/agent")
