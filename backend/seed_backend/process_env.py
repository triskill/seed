"""Environment boundaries between FastAPI, Pi, and untrusted children."""
from __future__ import annotations

import ctypes
import os
import sys

# Explicitly enumerated names from Pi 0.80.3's provider registry. Never form a
# variable name from user input and never pass these to shell/Flask children.
PI_CREDENTIAL_ENV_VARS = frozenset({
    "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_OAUTH_TOKEN", "ANTHROPIC_API_KEY", "ANT_LING_API_KEY",
    "OPENAI_API_KEY", "AZURE_OPENAI_API_KEY", "DEEPSEEK_API_KEY",
    "NVIDIA_API_KEY", "GEMINI_API_KEY", "GROQ_API_KEY", "CEREBRAS_API_KEY",
    "XAI_API_KEY", "FIREWORKS_API_KEY", "TOGETHER_API_KEY", "OPENROUTER_API_KEY",
    "AI_GATEWAY_API_KEY", "CLOUDFLARE_API_KEY", "ZAI_API_KEY", "ZAI_CODING_CN_API_KEY",
    "MISTRAL_API_KEY", "MINIMAX_API_KEY", "MINIMAX_CN_API_KEY",
    "MOONSHOT_API_KEY", "OPENCODE_API_KEY", "KIMI_API_KEY", "QWEN_TOKEN_PLAN_API_KEY",
    "XIAOMI_API_KEY", "XIAOMI_TOKEN_PLAN_CN_API_KEY", "XIAOMI_TOKEN_PLAN_AMS_API_KEY",
    "XIAOMI_TOKEN_PLAN_SGP_API_KEY", "HF_TOKEN", "GOOGLE_CLOUD_API_KEY",
    "CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_GATEWAY_ID", "AWS_BEARER_TOKEN_BEDROCK",
    "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN", "AWS_PROFILE",
    "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_ROLE_ARN", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
    "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "GOOGLE_APPLICATION_CREDENTIALS",
    "GOOGLE_CLOUD_PROJECT", "GOOGLE_CLOUD_LOCATION", "GCLOUD_PROJECT",
    "COPILOT_GITHUB_TOKEN", "CLOUDFLARE_API_TOKEN",
})

SEED_CAPABILITY_ENV = "SEED_RUNTIME_CAPABILITY"
PRIVATE_BACKEND_ENV_VARS = PI_CREDENTIAL_ENV_VARS | {SEED_CAPABILITY_ENV}


def untrusted_child_env() -> dict[str, str]:
    """Copy process settings while withholding Pi credentials/capabilities."""
    env = os.environ.copy()
    for name in PRIVATE_BACKEND_ENV_VARS:
        env.pop(name, None)
    from seed_backend.pi_settings import agent_dir
    env['PI_CODING_AGENT_DIR'] = str(agent_dir())
    return env


def harden_process_visibility() -> None:
    """Prevent same-UID children from reading this process's ``/proc`` env.

    The backend must hold the selected provider key to launch Pi, but shell,
    Flask, and Pi children must not recover it through ``/proc/$PPID/environ``.
    Linux inherits this setting by default. It is best effort for development
    hosts and non-Linux platforms.
    """
    if not sys.platform.startswith("linux"):
        return
    try:
        libc = ctypes.CDLL(None, use_errno=True)
        # PR_SET_DUMPABLE = 4; zero disables peer ptrace/proc inspection.
        libc.prctl(4, 0, 0, 0, 0)
    except (AttributeError, OSError, TypeError):
        return
