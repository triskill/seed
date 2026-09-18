package com.seed.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the curated Android provider list against the backend allowlist.

 The expected set lives in this test as data so a developer who
 changes one side without the other sees a clear failure.
 */
class ProviderCatalogTest {
    private val expected = setOf(
        "openai" to "OPENAI_API_KEY",
        "anthropic" to "ANTHROPIC_API_KEY",
        "google" to "GEMINI_API_KEY",
        "deepseek" to "DEEPSEEK_API_KEY",
        "groq" to "GROQ_API_KEY",
        "xai" to "XAI_API_KEY",
        "openrouter" to "OPENROUTER_API_KEY",
        "mistral" to "MISTRAL_API_KEY",
        "fireworks" to "FIREWORKS_API_KEY",
        "together" to "TOGETHER_API_KEY",
        "opencode" to "OPENCODE_API_KEY",
        "opencode-go" to "OPENCODE_API_KEY",
        "zai" to "ZAI_API_KEY",
        "minimax" to "MINIMAX_API_KEY",
        "moonshotai" to "MOONSHOT_API_KEY",
        "nvidia" to "NVIDIA_API_KEY",
        "cerebras" to "CEREBRAS_API_KEY",
        "kimi-coding" to "KIMI_API_KEY",
    )

    @Test
    fun everyExpectedProviderIsPresent() {
        val actual = ProviderCatalog.PROVIDERS.map { it.id to it.apiKeyEnvironment }
        assertEquals(expected, actual.toSet())
    }

    @Test
    fun noDuplicateProviderIds() {
        val ids = ProviderCatalog.PROVIDERS.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun findReturnsKnownProvider() {
        val openai = ProviderCatalog.find("openai")
        assertTrue(openai != null)
        assertEquals("OPENAI_API_KEY", openai!!.apiKeyEnvironment)
    }

    @Test
    fun findReturnsNullForUnknownProvider() {
        assertEquals(null, ProviderCatalog.find("nope"))
    }
}
