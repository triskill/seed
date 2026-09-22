"""Regression tests for Pi control and credential-isolation launch settings."""
from __future__ import annotations

import pytest

from seed_backend.orchestrator import pi_cmd_for_role, pi_env_for_role
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS, SEED_CAPABILITY_ENV


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Every curated credential / capability env var starts unset."""
    for name in PI_CREDENTIAL_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.delenv("SEED_RUNTIME_CAPABILITY", raising=False)
    monkeypatch.delenv("SEED_PI_MODEL", raising=False)
    monkeypatch.delenv("SEED_PI_PROVIDER", raising=False)
    monkeypatch.delenv("SEED_PI_THINKING", raising=False)
    yield


def test_normal_middleman_argv_omits_control_role_flags():
    argv = pi_cmd_for_role("middleman")
    assert "--no-tools" not in argv
    assert "--models" not in argv
    # Sanity: middleman still receives --provider/--model/--thinking.
    assert "--provider" in argv
    assert "--model" in argv
    assert "--thinking" in argv


def test_control_role_argv_uses_models_glob_when_model_unset(monkeypatch):
    monkeypatch.setenv("SEED_PI_PROVIDER", "openai")
    argv = pi_cmd_for_role("control")
    assert "--no-tools" in argv
    assert "--no-session" in argv
    assert "--no-extensions" in argv
    assert "--models" in argv
    assert argv[argv.index("--models") + 1] == "openai/*"


def test_control_role_argv_uses_explicit_model_when_set(monkeypatch):
    monkeypatch.setenv("SEED_PI_PROVIDER", "openai")
    monkeypatch.setenv("SEED_PI_MODEL", "gpt-4o")
    argv = pi_cmd_for_role("control")
    # When the user has saved a model, control uses it like normal roles.
    assert "--model" in argv
    assert argv[argv.index("--model") + 1] == "gpt-4o"
    assert "--models" not in argv
    # Control role is still read-only / headless.
    assert "--no-tools" in argv


def test_control_env_strips_runtime_capability(monkeypatch):
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")
    env = pi_env_for_role("control")
    assert "SEED_RUNTIME_CAPABILITY" not in env
    # The capability env var constant must be the one used elsewhere.
    assert SEED_CAPABILITY_ENV == "SEED_RUNTIME_CAPABILITY"


def test_normal_middleman_env_keeps_provider_credential_only(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-openai")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-anthropic")
    monkeypatch.setenv("SEED_PI_PROVIDER", "openai")
    env = pi_env_for_role("middleman")
    assert env.get("OPENAI_API_KEY") == "sk-openai"
    assert "ANTHROPIC_API_KEY" not in env


def test_control_role_credential_allowlist_uses_provider_allowlist(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-openai")
    monkeypatch.setenv("SEED_PI_PROVIDER", "openai")
    env = pi_env_for_role("control")
    assert env.get("OPENAI_API_KEY") == "sk-openai"


def test_unknown_role_raises_value_error():
    with pytest.raises(ValueError):
        pi_cmd_for_role("middleperson")  # not a real role
    with pytest.raises(ValueError):
        pi_env_for_role("middleperson")
