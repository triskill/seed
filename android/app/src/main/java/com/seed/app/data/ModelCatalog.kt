package com.seed.app.data

/** Provider is curated locally; model IDs and capabilities come only from Pi. */
data class ProviderOption(
    val id: String,
    val displayName: String,
    val apiKeyEnvironment: String?,
)

object ProviderCatalog {
    val PROVIDERS: List<ProviderOption> = listOf(
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

    fun find(id: String): ProviderOption? = PROVIDERS.firstOrNull { it.id == id }
}

data class ModelOption(
    val provider: String,
    val id: String,
    val name: String,
    val contextWindow: Int?,
    val maxTokens: Int?,
    val input: List<String>,
    val supportsThinking: Boolean,
    val thinkingLevels: List<String>,
)

data class ModelCatalog(
    val models: List<ModelOption> = emptyList(),
) {
    fun modelsFor(provider: String): List<ModelOption> = models.filter { it.provider == provider }
}
