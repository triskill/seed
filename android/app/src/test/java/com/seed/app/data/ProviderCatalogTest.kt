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
        ProviderOption("openai", "OpenAI", "OPENAI_API_KEY"),
        ProviderOption("anthropic", "Anthropic", "ANTHROPIC_API_KEY"),
        ProviderOption("google", "Google Gemini", "GEMINI_API_KEY"),
        ProviderOption("deepseek", "DeepSeek", "DEEPSEEK_API_KEY"),
        ProviderOption("groq", "Groq", "GROQ_API_KEY"),
        ProviderOption("xai", "xAI", "XAI_API_KEY"),
        ProviderOption("openrouter", "OpenRouter", "OPENROUTER_API_KEY"),
        ProviderOption("mistral", "Mistral", "MISTRAL_API_KEY"),
        ProviderOption("fireworks", "Fireworks", "FIREWORKS_API_KEY"),
        ProviderOption("together", "Together", "TOGETHER_API_KEY"),
        ProviderOption("opencode", "OpenCode", "OPENCODE_API_KEY"),
        ProviderOption("opencode-go", "OpenCode Go", "OPENCODE_API_KEY"),
        ProviderOption("zai", "Z.AI", "ZAI_API_KEY"),
        ProviderOption("minimax", "MiniMax", "MINIMAX_API_KEY"),
        ProviderOption("moonshotai", "Moonshot", "MOONSHOT_API_KEY"),
        ProviderOption("nvidia", "NVIDIA", "NVIDIA_API_KEY"),
        ProviderOption("cerebras", "Cerebras", "CEREBRAS_API_KEY"),
        ProviderOption("kimi-coding", "Kimi for Coding", "KIMI_API_KEY"),
    )

    @Test
    fun everyExpectedProviderIsPresent() {
        assertEquals(expected, ProviderCatalog.PROVIDERS.toSet())
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
