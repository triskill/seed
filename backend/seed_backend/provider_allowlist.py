"""Curated provider -> API-key environment-variable allowlist.

A single source of truth shared by `pi_env_for_role` and the
credential-env contract tests. The mapping is curated locally;
only IDs present here can be passed to Pi as `--provider` or
used as the selected credential.
"""
from __future__ import annotations

PROVIDERS: dict[str, str | None] = {
    "openai": "OPENAI_API_KEY",
    "anthropic": "ANTHROPIC_API_KEY",
    "google": "GEMINI_API_KEY",
    "deepseek": "DEEPSEEK_API_KEY",
    "groq": "GROQ_API_KEY",
    "xai": "XAI_API_KEY",
    "openrouter": "OPENROUTER_API_KEY",
    "mistral": "MISTRAL_API_KEY",
    "fireworks": "FIREWORKS_API_KEY",
    "together": "TOGETHER_API_KEY",
    "opencode": "OPENCODE_API_KEY",
    "opencode-go": "OPENCODE_API_KEY",
    "zai": "ZAI_API_KEY",
    "minimax": "MINIMAX_API_KEY",
    "moonshotai": "MOONSHOT_API_KEY",
    "nvidia": "NVIDIA_API_KEY",
    "cerebras": "CEREBRAS_API_KEY",
    "kimi-coding": "KIMI_API_KEY",
}


def credential_env_for(provider: str) -> str | None:
    return PROVIDERS.get(provider.strip().lower())
