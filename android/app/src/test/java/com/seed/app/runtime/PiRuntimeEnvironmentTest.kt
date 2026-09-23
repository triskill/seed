package com.seed.app.runtime

import com.seed.app.ui.settings.SettingsForm
import org.junit.Assert.assertEquals
import org.junit.Test

class PiRuntimeEnvironmentTest {
    @Test fun `startup never overrides backend Pi selection or passes legacy credentials`() {
        val form = SettingsForm(provider = "openai", model = "gpt-4o", apiKey = "secret", thinkingLevel = "high")
        assertEquals(mapOf("PI_CODING_AGENT_DIR" to "/home/seed/.pi/agent"), form.toPiRuntimeEnvironment())
        assertEquals(mapOf("PI_CODING_AGENT_DIR" to "/home/seed/.pi/agent"),
            (null as SettingsForm?).toPiRuntimeEnvironment())
    }
}
