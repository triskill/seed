"""Tests for the FastAPI service lifespan + orchestrator (Task 3.1).

The lifespan is responsible for bringing up the two `pi` agent
processes (middle-man and worker) before the orchestrator starts
accepting requests, and for tearing them down cleanly on shutdown.
This test pins down the contract: after the lifespan runs, an
Orchestrator is reachable via `app.state.orchestrator`, it owns two
live `PiRunner` instances with distinct PIDs, and a shutdown of the
lifespan terminates both children.

The default production `pi` cmd is monkey-patched to the local
`fake_pi.py` fixture so the test runs without a real `pi` install
or an LLM. The fake pi is a Python script that blocks on stdin
until a line is written, then emits three progress events and a
`done` marker before exiting. As long as we don't send it any
input it stays alive across the assertion window, which is what
the test relies on.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app


def _fake_pi_cmd() -> list[str]:
    """Argv that points at the in-tree fake pi fixture."""
    return [sys.executable, str(Path(__file__).parent / "fixtures" / "fake_pi.py")]


@pytest.fixture
def orchestrator_client(monkeypatch):
    """TestClient wired to a lifespan that spawns fake pi for both roles.

    Yields the TestClient; the `with` block triggers the lifespan on
    entry and tears it down on exit. Yields a fully-initialized
    orchestrator reachable via `client.app.state.orchestrator`.
    """
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: _fake_pi_cmd())
    with TestClient(app) as client:
        yield client


def test_lifespan_creates_orchestrator_with_two_runners(orchestrator_client):
    """app.state.orchestrator has middleman + worker PiRunners with valid PIDs.

    Two distinct PIDs (and a non-None orchestrator) prove the lifespan
    actually launched the fake pi twice — one per role. If either
    runner hadn't been started, its `pid` would be None.
    """
    orch = orchestrator_client.app.state.orchestrator
    assert orch is not None
    assert orch.middleman.pid is not None and orch.middleman.pid > 0
    assert orch.worker.pid is not None and orch.worker.pid > 0
    # Two distinct child processes.
    assert orch.middleman.pid != orch.worker.pid


def test_lifespan_orchestrator_middleman_and_worker_have_roles(orchestrator_client):
    """The two runners are tagged with the right roles.

    Phase 3.4 uses the role to decide which tool calls to allow
    (middle-man is read-only); pinning the role here makes sure
    the lifespan wires the right role to the right runner.
    """
    orch = orchestrator_client.app.state.orchestrator
    assert orch.middleman.role == "middleman"
    assert orch.worker.role == "worker"


def test_lifespan_enforces_middleman_read_only_tools(orchestrator_client):
    """Production wiring enables the event-filter backstop for one role only."""
    orch = orchestrator_client.app.state.orchestrator
    assert orch.middleman.read_only_tools == {"read", "grep", "find", "ls"}
    assert orch.worker.read_only_tools is None
    assert orch.middleman.env is not None
    assert orch.worker.env is not None
    assert orch.middleman.env["SEED_APP_URL"] == orch.worker.env["SEED_APP_URL"]


def test_lifespan_stops_runners_on_shutdown(monkeypatch):
    """Lifespan shutdown terminates both PiRunners (pid reset to None)."""
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: _fake_pi_cmd())
    orch_ref: list = []
    with TestClient(app) as client:
        orch_ref.append(client.app.state.orchestrator)
        # Sanity: both runners are alive during the lifespan.
        assert orch_ref[0].middleman.pid is not None
        assert orch_ref[0].worker.pid is not None
    # After the lifespan exits, both runners should have been
    # stopped by Orchestrator.stop() and have their pids cleared.
    assert orch_ref[0].middleman.pid is None
    assert orch_ref[0].worker.pid is None


def test_lifespan_survives_missing_pi_command(monkeypatch):
    """Lifespan doesn't crash when the configured pi cmd is unrunnable.

    Production may run this in an environment where `pi` is not yet
    installed (e.g. before Phase 4). The orchestrator should still
    come up — `/health` and `/shell/exec` work, only `/chat` will
    fail when someone tries to send a message. The lifespan swallows
    the spawn failure and leaves the orchestrator in app.state.
    """
    # Popen reports this missing executable synchronously; the lifespan logs
    # the failure and keeps the non-agent application surface available.
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: [
        "/nonexistent/pi/binary/that/does/not/exist"
    ])
    with TestClient(app) as client:
        # The orchestrator exists (even if its runners are dead).
        assert client.app.state.orchestrator is not None
        # Flask + /health still work — orchestrator failure didn't
        # take down the app.
        response = client.get("/health")
        assert response.status_code == 200
