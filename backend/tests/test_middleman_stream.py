"""Tests for middle-man output streaming to /chat (Task 3.3).

The /chat WS contract has two halves: client -> server (a
user_message frame) and server -> client (events streamed
from the middle-man pi subprocess). Task 3.2 covered the
first half; this file covers the second.

The orchestrator spawns a background read loop in
`Orchestrator.start()` (Task 3.3) that consumes lines from
the middle-man and broadcasts each one to every subscriber
queue. The chat route in `chat.py` registers itself as a
subscriber, forwards each broadcast event to the WebSocket
as a JSON text frame, and unsubscribes on disconnect.

Tests use the basic `fake_pi.py` fixture (which emits three
JSONL progress events + a `done` marker) so we can assert
the WS sees the same shapes the real `pi` would emit.
"""
from __future__ import annotations

import asyncio
import json
import sys
import time
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app


def _fake_pi_cmd() -> list[str]:
    return [
        sys.executable,
        str(Path(__file__).parent / "fixtures" / "fake_pi.py"),
    ]


@pytest.fixture
def stream_client(monkeypatch):
    """TestClient wired to a lifespan that spawns the basic fake pi
    (3 progress events + done) for both roles."""
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: _fake_pi_cmd())
    with TestClient(app) as client:
        yield client


def _drain_progress(ws, n: int, timeout_s: float = 5.0) -> list[dict]:
    """Receive up to `n` frames from the WS as parsed JSON dicts.

    Each call to `ws.receive_text()` is synchronous from the
    test thread's perspective, but the underlying call is
    blocking until a frame arrives. We bound the total time
    with a polling loop so a stuck test fails fast.
    """
    deadline = time.monotonic() + timeout_s
    out: list[dict] = []
    while len(out) < n and time.monotonic() < deadline:
        # Receive in a tight loop. In practice the events arrive
        # in a single ~300ms burst (3 events * 100ms apart), so
        # we should hit `n` in well under the timeout.
        frame = ws.receive_text()
        out.append(json.loads(frame))
    return out


def test_middle_man_progress_streams_to_chat_ws(stream_client):
    """Three progress events from the middle-man reach the WS client.

    The basic fake_pi emits three JSONL progress events (one
    per `step i of 3` for i=1,2,3) and then a `done` marker.
    The orchestrator's read loop should forward each progress
    event as a WS text frame; the test asserts the count and
    the embedded step counter.

    Each WS frame is a `{"type": "middleman_line", "line": ...}`
    envelope (Task 3.3 wire format). The `line` field is the
    raw JSONL string the middle-man emitted, so the test
    JSON-decodes it before inspecting.
    """
    client = stream_client
    with client.websocket_connect("/chat") as ws:
        ws.send_text(json.dumps({"type": "user_message", "text": "stream test"}))
        # Pull three progress events.
        frames = _drain_progress(ws, 3, timeout_s=5.0)

    assert len(frames) == 3, f"expected 3 frames, got {frames!r}"
    events = []
    for fr in frames:
        assert fr["type"] == "middleman_line", fr
        events.append(json.loads(fr["line"]))
    for i, ev in enumerate(events, start=1):
        assert ev["type"] == "progress"
        assert ev["kind"] == "thought"
        # The fake pi embeds the user prompt and a step counter
        # in the first/third events respectively; we just check
        # the step counter is monotonically increasing.
        assert f"step {i} of 3" in ev["text"], ev


def test_middle_man_line_carries_user_prompt(stream_client):
    """The first progress event embeds the user prompt in its text.

    The fake pi echoes the user prompt in its first event's
    `text` field, which proves the orchestrator is forwarding
    *exactly* what the middle-man emitted (no truncation, no
    re-encoding).
    """
    client = stream_client
    with client.websocket_connect("/chat") as ws:
        ws.send_text(json.dumps({"type": "user_message", "text": "hello there"}))
        frames = _drain_progress(ws, 1, timeout_s=5.0)

    assert len(frames) == 1
    assert frames[0]["type"] == "middleman_line"
    inner = json.loads(frames[0]["line"])
    assert "'hello there'" in inner["text"]


def test_ws_unsubscribes_on_disconnect(stream_client):
    """Disconnecting the WS unregisters the subscriber queue.

    After the `with` block exits, the orchestrator should have
    no leftover subscribers. We check by reading the
    orchestrator's internal set after the lifespan teardown.
    """
    client = stream_client
    orch = client.app.state.orchestrator
    with client.websocket_connect("/chat") as ws:
        ws.send_text(json.dumps({"type": "user_message", "text": "x"}))
        # Let one frame through so the read loop has work to do.
        ws.receive_text()
        # During the WS, there should be at least one subscriber.
        # (The chat handler registers a queue on accept().)
        assert len(orch._subscribers) >= 1
    # After the WS disconnects, the subscriber should be gone.
    assert len(orch._subscribers) == 0
