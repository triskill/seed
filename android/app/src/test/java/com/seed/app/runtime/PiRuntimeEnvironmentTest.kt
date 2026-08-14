package com.seed.app.runtime

import com.seed.app.ui.settings.SettingsForm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PiRuntimeEnvironmentTest {
    @Test
    fun `fresh install preserves packaged pi defaults`() {
        assertTrue((null as SettingsForm?).toPiRuntimeEnvironment().isEmpty())
    }

    @Test
    fun `openai settings map provider model and encrypted credential`() {
        val environment = SettingsForm(
            provider = " openai ",
            model = " gpt-4o ",
            apiKey = "sk-secret",
        ).toPiRuntimeEnvironment()

        assertEquals("openai", environment["SEED_PI_PROVIDER"])
        assertEquals("gpt-4o", environment["SEED_PI_MODEL"])
        assertEquals("sk-secret", environment["OPENAI_API_KEY"])
        assertFalse(environment.containsKey("ANTHROPIC_API_KEY"))
    }

    @Test
    fun `supported provider credentials use explicit pi variable allowlist`() {
        val cases = listOf(
            "anthropic" to "ANTHROPIC_API_KEY",
            "opencode" to "OPENCODE_API_KEY",
            "opencode-go" to "OPENCODE_API_KEY",
        )

        cases.forEach { (provider, expectedVariable) ->
            val environment = SettingsForm(
                provider = provider,
                model = "model",
                apiKey = "secret",
            ).toPiRuntimeEnvironment()
            assertEquals("secret", environment[expectedVariable])
            assertEquals(3, environment.size)
        }
    }

    @Test
    fun `empty key and local provider inject no credential`() {
        val emptyKey = SettingsForm(
            provider = "anthropic",
            model = "claude",
            apiKey = "",
        ).toPiRuntimeEnvironment()
        val local = SettingsForm(
            provider = "local",
            model = "local-model",
            apiKey = "",
        ).toPiRuntimeEnvironment()

        assertEquals(setOf("SEED_PI_PROVIDER", "SEED_PI_MODEL"), emptyKey.keys)
        assertEquals(setOf("SEED_PI_PROVIDER", "SEED_PI_MODEL"), local.keys)
    }

    @Test
    fun `unknown provider cannot dynamically create a credential variable`() {
        val form = SettingsForm(
            provider = "attacker-controlled",
            model = "model",
            apiKey = "secret",
        )

        assertThrows(IllegalArgumentException::class.java) {
            form.toPiRuntimeEnvironment()
        }
    }

    @Test
    fun `invalid environment values are rejected before ProcessBuilder`() {
        listOf(
            SettingsForm(provider = " ", model = "model"),
            SettingsForm(provider = "openai", model = " "),
            SettingsForm(provider = "openai\u0000bad", model = "model"),
            SettingsForm(provider = "openai", model = "model", apiKey = "bad\nkey"),
        ).forEach { form ->
            assertThrows(IllegalArgumentException::class.java) {
                form.toPiRuntimeEnvironment()
            }
        }
    }
}
