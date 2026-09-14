package com.seed.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCatalogTest {
    @Test
    fun providerCatalogContainsOnlyExplicitCredentialMappings() {
        assertTrue(ProviderCatalog.PROVIDERS.isNotEmpty())
        assertTrue(ProviderCatalog.PROVIDERS.all { it.apiKeyEnvironment != null })
        assertEquals("OPENAI_API_KEY", ProviderCatalog.find("openai")?.apiKeyEnvironment)
    }

    @Test
    fun modelsAreScopedByExactProvider() {
        val catalog = ModelCatalog(listOf(
            ModelOption("openai", "gpt", "GPT", 1, 1, listOf("text"), false, listOf("off")),
            ModelOption("anthropic", "claude", "Claude", 1, 1, listOf("text"), true, listOf("off", "low")),
        ))
        assertEquals(listOf("gpt"), catalog.modelsFor("openai").map { it.id })
    }
}
