package com.seed.app.runtime

import com.seed.app.data.ProviderCatalog
import com.seed.app.ui.settings.SettingsForm

/**
 * Convert encrypted Android settings into the environment inherited by the
 * embedded uvicorn process and its two pi children.
 *
 * Provider/model selection uses Seed's existing environment overrides. The
 * API key uses an explicitly allowlisted provider variable, keeping the secret
 * out of command-line arguments, loopback HTTP, and the generated rootfs. The
 * returned values are sensitive and must never be logged.
 */
internal fun SettingsForm?.toPiRuntimeEnvironment(
    allowMissingModel: Boolean = false,
): Map<String, String> {
    if (this == null) return emptyMap()

    val normalizedProvider = provider.trim()
    val normalizedModel = model.trim()
    require(normalizedProvider.isNotEmpty()) { "Saved pi provider is blank" }
    require(ProviderCatalog.find(normalizedProvider) != null) {
        "Saved pi provider is not supported"
    }
    require(allowMissingModel || normalizedModel.isNotEmpty()) { "Saved pi model is blank" }
    require(normalizedProvider.none { it.isISOControl() }) {
        "Saved pi provider contains control characters"
    }
    require(normalizedModel.none { it.isISOControl() }) {
        "Saved pi model contains control characters"
    }
    require(apiKey.none { it.isISOControl() }) {
        "Saved pi API key contains control characters"
    }
    require(thinkingLevel in setOf("off", "minimal", "low", "medium", "high", "xhigh")) {
        "Saved pi thinking level is invalid"
    }

    val keyVariable = ProviderCatalog.find(normalizedProvider)?.apiKeyEnvironment
    require(apiKey.isEmpty() || keyVariable != null) {
        "Saved pi provider does not have an allowlisted API-key variable"
    }

    return buildMap {
        put("SEED_PI_PROVIDER", normalizedProvider)
        if (normalizedModel.isNotEmpty()) put("SEED_PI_MODEL", normalizedModel)
        put("SEED_PI_THINKING", thinkingLevel)
        if (apiKey.isNotEmpty() && keyVariable != null) {
            put(keyVariable, apiKey)
        }
    }
}
