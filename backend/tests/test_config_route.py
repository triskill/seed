"""Tests for the `PUT /config` route (Phase 6.5).

The route is the destination the Android Settings screen's
[com.seed.app.data.ConfigSync] PUTs the user's settings to.
It writes the payload to [seed_backend.service.DEFAULT_CONFIG_PATH]
via [seed_backend.config.Config.save].

These tests pin the contract: the right shape is written, the
file ends up at the right path, and the response is `{"ok": true}`.
They use a per-test `tmp_path`-based monkeypatch of
`DEFAULT_CONFIG_PATH` so the test never touches the real
`backend/config.json` (the file the dev stack actually reads).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app


def _fake_pi_cmd() -> list[str]:
    """Argv that points at the in-tree fake pi fixture.

    The lifespan calls `pi_cmd_for_role`, which by default would
    try to exec the real `pi` binary. For these tests we only
    care about the `/config` route — the `pi` runners are
    irrelevant — but the lifespan still spawns them, so we
    point at the in-tree fake pi (the same pattern
    [test_service_lifecycle] uses).
    """
    return [sys.executable, str(Path(__file__).parent / "fixtures" / "fake_pi.py")]


@pytest.fixture
def config_client(monkeypatch, tmp_path):
    """TestClient with `DEFAULT_CONFIG_PATH` pointed at `tmp_path`.

    Yields a `(client, config_path)` tuple:
      - `client` is the FastAPI TestClient (lifespan fires on
        enter, tears down on exit);
      - `config_path` is the temp file the `PUT /config` route
        will write to. Asserting on `config_path.read_text()`
        confirms the round-trip without touching the real
        dev `config.json`.
    """
    config_path = tmp_path / "config.json"
    monkeypatch.setattr(service, "DEFAULT_CONFIG_PATH", config_path)
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: _fake_pi_cmd())
    with TestClient(app) as client:
        yield client, config_path


def test_put_config_writes_payload_to_default_path(config_client):
    """A PUT /config with a full payload lands at DEFAULT_CONFIG_PATH
    in the on-disk JSON shape the Config dataclass writes.

    The on-disk shape is `{"provider", "model", "api_key", "ports"}` —
    retains an empty legacy `api_key`; Android no longer transfers secrets
    through this endpoint.
    """
    client, config_path = config_client
    response = client.put(
        "/config",
        json={
            "provider": "anthropic",
            "model": "claude-sonnet-4-5",
            "api_key": "must-not-be-persisted",
            "ports": {"backend": 7777, "flask": 7778},
        },
    )
    assert response.status_code == 200
    assert response.json() == {"ok": True}

    # The legacy secret is cleared rather than copied into plaintext. Compare
    # as a dict
    # (not a raw string) so a future cosmetic
    # change to Config.save (indentation,
    # key order) doesn't break this test.
    assert config_path.exists()
    on_disk = json.loads(config_path.read_text())
    assert on_disk == {
        "provider": "anthropic",
        "model": "claude-sonnet-4-5",
        "api_key": "",
        "ports": {"backend": 7777, "flask": 7778},
    }


def test_put_config_overwrites_existing_file(config_client, tmp_path):
    """A second PUT replaces the file's contents (not append)."""
    client, config_path = config_client
    # First write.
    client.put(
        "/config",
        json={
            "provider": "openai",
            "model": "gpt-4o",
            "api_key": "sk-first",
            "ports": {"backend": 7777, "flask": 7778},
        },
    )
    assert json.loads(config_path.read_text())["provider"] == "openai"
    # Second write (different provider + attempted legacy key).
    client.put(
        "/config",
        json={
            "provider": "anthropic",
            "model": "claude-sonnet-4-5",
            "api_key": "sk-second",
            "ports": {"backend": 7777, "flask": 7778},
        },
    )
    on_disk = json.loads(config_path.read_text())
    assert on_disk["provider"] == "anthropic"
    assert on_disk["api_key"] == ""


def test_put_config_accepts_omitted_api_key(config_client):
    """Android omits the legacy secret field; the backend persists empty."""
    client, config_path = config_client
    response = client.put(
        "/config",
        json={
            "provider": "anthropic",
            "model": "claude-sonnet-4-5",
            "ports": {"backend": 7777, "flask": 7778},
        },
    )
    assert response.status_code == 200
    assert json.loads(config_path.read_text())["api_key"] == ""


def test_put_config_rejects_empty_provider_with_422(config_client):
    """Pydantic min_length=1 on `provider` rejects empty strings.

    The Android side never sends an empty provider (the form
    starts at "openai" and the dropdown is free-form but the
    default is non-empty), but a hand-rolled curl could. The
    422 is FastAPI's standard validation error.
    """
    client, _ = config_client
    response = client.put(
        "/config",
        json={
            "provider": "",
            "model": "claude-sonnet-4-5",
            "api_key": "",
            "ports": {"backend": 7777, "flask": 7778},
        },
    )
    assert response.status_code == 422


def test_put_config_accepts_custom_ports(config_client):
    """Non-default ports (e.g. 8888/8889) are written as-is.

    The Android form's port fields are user-editable. The
    backend doesn't validate they're in the privileged-port
    range — that's the orchestrator's runtime concern (and
    `bind: address already in use` will surface it then).
    """
    client, config_path = config_client
    response = client.put(
        "/config",
        json={
            "provider": "anthropic",
            "model": "claude-sonnet-4-5",
            "api_key": "sk-test-1234",
            "ports": {"backend": 8888, "flask": 8889},
        },
    )
    assert response.status_code == 200
    assert json.loads(config_path.read_text())["ports"] == {
        "backend": 8888,
        "flask": 8889,
    }
