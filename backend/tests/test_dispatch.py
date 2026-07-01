"""Tests for middle-man dispatch JSON detection (Task 3.4).

The middle-man agent emits a fenced ```json\n{...}\n``` block
when it has gathered enough intent to hand the task off to
the worker. The orchestrator's middle-man read loop (Task 3.3)
scans each line for this block; when one is found, the parsed
JSON is forwarded to the worker pi.

The wire format the test exercises:

  middle-man stdout:
      thinking about the user's request
      ```json
      {"intent": "build_feature", "feature": "habit_tracker", "spec": "..."}
      ```
      done

  worker stdin (what the orchestrator sends):
      {"intent": "build_feature", "feature": "habit_tracker", "spec": "..."}

The test confirms the worker received the parsed JSON by
checking the log file `fake_pi_log.py` writes when the worker
process reads its stdin.
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


def _fake_pi_dispatch_cmd() -> list[str]:
    return [
        sys.executable,
        str(Path(__file__).parent / "fixtures" / "fake_pi_dispatch.py"),
    ]


def _fake_pi_log_cmd(log_path: Path) -> list[str]:
    return [
        sys.executable,
        str(Path(__file__).parent / "fixtures" / "fake_pi_log.py"),
        "--log",
        str(log_path),
    ]


@pytest.fixture
def dispatch_client(monkeypatch, tmp_path):
    """TestClient wired to a lifespan where the middle-man emits a
    dispatch block and the worker logs its stdin to a file."""
    w_log = tmp_path / "worker.log"
    mm_log = tmp_path / "middleman.log"

    def cmd_for_role(role: str) -> list[str]:
        if role == "middleman":
            return _fake_pi_dispatch_cmd()
        if role == "worker":
            return _fake_pi_log_cmd(w_log)
        raise ValueError(f"unknown role: {role!r}")

    monkeypatch.setattr(service, "pi_cmd_for_role", cmd_for_role)
    with TestClient(app) as client:
        yield {"client": client, "w_log": w_log}


def _wait_for_file(path: Path, timeout_s: float = 5.0) -> bool:
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if path.exists():
            return True
        time.sleep(0.05)
    return False


def test_dispatch_json_triggers_worker(dispatch_client):
    """A dispatch block in middle-man output is forwarded to the worker.

    The fake middle-man emits one fenced ```json block; the
    orchestrator's read loop should parse it, send the parsed
    JSON to the worker pi's stdin, and the worker (which is
    pointed at `fake_pi_log.py`) writes whatever it received
    to a log file. The test reads that file and asserts the
    dispatch JSON is there.
    """
    client = dispatch_client["client"]
    w_log = dispatch_client["w_log"]

    with client.websocket_connect("/chat") as ws:
        ws.send_text(
            json.dumps({"type": "user_message", "text": "i want a habit tracker"})
        )

    assert _wait_for_file(w_log), f"worker log not written: {w_log}"
    raw = w_log.read_text().strip()
    # The fake_pi_log fixture writes the literal line it read
    # from stdin, which is the JSON the orchestrator sent. So
    # the file content should be parseable as JSON and should
    # contain the dispatch fields the fixture emitted.
    parsed = json.loads(raw)
    assert parsed["intent"] == "build_feature"
    assert parsed["feature"] == "habit_tracker"
    assert "habit tracker" in parsed["spec"].lower()
