package com.seed.app.runtime

import com.seed.app.ui.settings.SettingsForm
import java.util.Locale

/**
 * Convert encrypted Android settings into the environment inherited by the
 * embedded uvicorn process and its two pi children.
 *
 * Provider/model selection uses Seed's existing environment overrides. The
 * API key uses an explicitly allowlisted provider variable, keeping the secret
 * out of command-line arguments, loopback HTTP, and the generated rootfs. The
 * returned values are sensitive and must never be logged.
 */
internal fun SettingsForm?.toPiRuntimeEnvironment(): Map<String, String> {
    if (this == null) return emptyMap()

    val normalizedProvider = provider.trim()
    val normalizedModel = model.trim()
    require(normalizedProvider.isNotEmpty()) { "Saved pi provider is blank" }
    require(normalizedModel.isNotEmpty()) { "Saved pi model is blank" }
    require(normalizedProvider.none { it.isISOControl() }) {
        "Saved pi provider contains control characters"
    }
    require(normalizedModel.none { it.isISOControl() }) {
        "Saved pi model contains control characters"
    }
    require(apiKey.none { it.isISOControl() }) {
        "Saved pi API key contains control characters"
    }

    val keyVariable = providerApiKeyVariable(normalizedProvider)
    require(apiKey.isEmpty() || keyVariable != null) {
        "Saved pi provider does not have an allowlisted API-key variable"
    }

    return buildMap {
        put("SEED_PI_PROVIDER", normalizedProvider)
        put("SEED_PI_MODEL", normalizedModel)
        if (apiKey.isNotEmpty() && keyVariable != null) {
            put(keyVariable, apiKey)
        }
    }
}

/** Return pi's credential variable, or null for local/unknown providers. */
private fun providerApiKeyVariable(provider: String): String? =
    when (provider.lowercase(Locale.US)) {
        "openai" -> "OPENAI_API_KEY"
        "anthropic" -> "ANTHROPIC_API_KEY"
        "opencode", "opencode-go" -> "OPENCODE_API_KEY"
        "local" -> null
        else -> null
    }
