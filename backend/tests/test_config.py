"""Tests for the Config dataclass.

Task 0.3: validates JSON load/save round-trip and that defaults are
applied for missing fields. The Config is small and self-contained;
later tasks (Flask manager, pi runner, settings sync) build on it.
"""
import json
from pathlib import Path

from seed_backend.config import Config


def test_load_config_returns_config_with_expected_fields(tmp_path: Path):
    """Config.load returns a Config with provider, model, api_key, ports."""
    cfg_path = tmp_path / "config.json"
    cfg_path.write_text(
        json.dumps(
            {
                "provider": "openai",
                "model": "gpt-5",
                "api_key": "sk-test-123",
                "ports": {"backend": 8888, "flask": 8889},
            }
        )
    )

    config = Config.load(cfg_path)

    assert isinstance(config, Config)
    assert config.provider == "openai"
    assert config.model == "gpt-5"
    assert config.api_key == "sk-test-123"
    assert config.ports == {"backend": 8888, "flask": 8889}


def test_save_writes_json_and_roundtrips(tmp_path: Path):
    """Config.save writes the fields as JSON; load can read it back."""
    cfg_path = tmp_path / "config.json"
    original = Config(
        provider="openai",
        model="gpt-5",
        api_key="sk-test-123",
        ports={"backend": 8888, "flask": 8889},
    )

    original.save(cfg_path)
    roundtripped = Config.load(cfg_path)

    assert roundtripped == original


def test_defaults_applied_when_constructed_empty():
    """Config() uses the documented defaults for all fields."""
    config = Config()

    assert config.provider == "anthropic"
    assert config.model == "claude-sonnet-4"
    assert config.ports == {"backend": 7777, "flask": 7778}
