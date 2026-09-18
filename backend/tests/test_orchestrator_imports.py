"""Regression tests for `pi_env_for_role`'s credential-env whitelist.

`pi_env_for_role` must retain exactly one credential env var per
selection: the one matching `SEED_PI_PROVIDER`. All other entries in
`PI_CREDENTIAL_ENV_VARS` must be stripped so a misconfigured host
shell with multiple `*_API_KEY` values never accidentally reaches Pi.
"""
from __future__ import annotations

import pytest

from seed_backend.orchestrator import pi_env_for_role
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS


@pytest.fixture(autouse=True)
def _clean_env(monkeypatch):
    """Strip every curated credential env var so each test starts clean."""
    for name in PI_CREDENTIAL_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.delenv("SEED_RUNTIME_CAPABILITY", raising=False)
    yield


def test_only_selected_provider_credential_survives(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-openai")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-anthropic")
    monkeypatch.setenv("GEMINI_API_KEY", "sk-gemini")
    monkeypatch.setenv("SEED_PI_PROVIDER", "openai")

    env = pi_env_for_role("middleman")

    assert env.get("OPENAI_API_KEY") == "sk-openai"
    assert "ANTHROPIC_API_KEY" not in env
    assert "GEMINI_API_KEY" not in env
    assert "SEED_RUNTIME_CAPABILITY" not in env


def test_no_credential_survives_when_provider_unknown(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-openai")
    monkeypatch.setenv("ANTHROPIC_API_KEY", "sk-anthropic")
    monkeypatch.setenv("SEED_PI_PROVIDER", "made-up-provider")

    env = pi_env_for_role("middleman")

    assert "OPENAI_API_KEY" not in env
    assert "ANTHROPIC_API_KEY" not in env


def test_provider_id_normalisation(monkeypatch):
    monkeypatch.setenv("OPENAI_API_KEY", "sk-openai")
    monkeypatch.setenv("SEED_PI_PROVIDER", "  OpenAI  ")

    env = pi_env_for_role("middleman")

    assert env.get("OPENAI_API_KEY") == "sk-openai"
