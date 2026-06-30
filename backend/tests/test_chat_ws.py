"""Tests for the WebSocket /chat endpoint (Task 3.2).

The /chat endpoint is the single ingress for user messages typed
into the Android app's Chat screen. Task 3.2 covers just the
first half of the contract: accept the connection, receive a
user_message frame, and forward it to the middle-man. The
streaming of middle-man + worker output back to the client is
added in Tasks 3.3 and 3.5.

The "did the middle-man receive it?" check is done by pointing
the middle-man at a fake pi (`fake_pi_log.py`) that writes the
received prompt to a log file; the test reads the file. This
avoids reaching into the runner's internal queue from a
different event loop (the test client's portal runs in a
thread, and the queue is bound to that thread's loop).
"""
from __future__ import annotations

import json
import sys
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app


def _fake_pi_log_cmd(log_path: Path) -> list[str]:
    return [
        sys.executable,
        str(Path(__file__).parent / "fixtures" / "fake_pi_log.py"),
        "--log",
        str(log_path),
    ]


def _make_log_cmd_factory(mm_log: Path, w_log: Path):
    """Build a pi_cmd_for_role that points each role at a distinct log file."""

    def cmd_for_role(role: str) -> list[str]:
        if role == "middleman":
            return _fake_pi_log_cmd(mm_log)
        if role == "worker":
            return _fake_pi_log_cmd(w_log)
        raise ValueError(f"unknown role: {role!r}")

    return cmd_for_role


@pytest.fixture
def chat_client(monkeypatch, tmp_path):
    """TestClient wired to a lifespan that spawns fake_pi_log for both roles.

    The fixture provides the per-role log paths via the yielded dict so
    tests can assert on the files after the WS round-trip.
    """
    mm_log = tmp_path / "middleman.log"
    w_log = tmp_path / "worker.log"
    monkeypatch.setattr(
        service, "pi_cmd_for_role", _make_log_cmd_factory(mm_log, w_log)
    )
    with TestClient(app) as client:
        yield {"client": client, "mm_log": mm_log, "w_log": w_log}


def _wait_for_file(path: Path, timeout_s: float = 5.0) -> bool:
    """Poll `path` until it exists or the timeout elapses. Returns
    whether the file was seen."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if path.exists():
            return True
        time.sleep(0.05)
    return False


def test_chat_ws_forwards_user_message_to_middle_man(chat_client):
    """A user_message over /chat reaches the middle-man pi subprocess.

    The fake pi writes the received prompt to a log file; we
    wait for the file to appear (up to 5s, well over the
    millisecond-scale child-process latency) and then read it.
    """
    client = chat_client["client"]
    mm_log = chat_client["mm_log"]

    with client.websocket_connect("/chat") as ws:
        ws.send_text(json.dumps({"type": "user_message", "text": "hello pi"}))

    assert _wait_for_file(mm_log), f"middleman log not written: {mm_log}"
    contents = mm_log.read_text()
    assert "hello pi" in contents


def test_chat_ws_does_not_forward_to_worker(chat_client):
    """In Task 3.2 the worker is not yet wired to receive the user message.

    Only the middle-man should log the prompt. The dispatch
    JSON -> worker path is added in Task 3.4; before then the
    worker just sits on its (unwritten) stdin and its log
    stays empty.
    """
    client = chat_client["client"]
    w_log = chat_client["w_log"]

    with client.websocket_connect("/chat") as ws:
        ws.send_text(json.dumps({"type": "user_message", "text": "hi worker"}))

    # Wait a short window for the middleman to process (the worker
    # would receive it later in 3.4 via dispatch JSON). If 3.4
    # were active, the worker log would appear; here it must not.
    time.sleep(0.3)
    assert not w_log.exists(), (
        f"worker should not have received a direct user_message in 3.2: {w_log}"
    )


def test_chat_ws_accepts_connection(chat_client):
    """The /chat endpoint accepts the WebSocket upgrade.

    Smoke test: the connection succeeds and we can close it
    without errors. More substantive behaviour is covered by
    the other tests in this file.
    """
    client = chat_client["client"]
    with client.websocket_connect("/chat") as ws:
        # We can ping the connection to confirm it's live.
        ws.send_text(json.dumps({"type": "ping"}))
