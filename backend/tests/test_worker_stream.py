"""Tests for worker output streaming to /chat (Task 3.5).

Task 3.3 streams the middle-man's output; Task 3.5 streams
the worker's output the same way, but tagged with
`type=worker_line` so the chat UI can render the two
agents distinctly (the middle-man's text is "thinking",
the worker's text is "building").

End-to-end: the test sends a user message to the chat WS,
the middle-man fixture emits a dispatch, the orchestrator
forwards it to the worker (Task 3.4), and the worker
fixture emits three progress events. The test asserts the
WS sees those three events tagged as `worker_line`.
"""
from __future__ import annotations

import json
import queue
import sys
import threading
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


def _fake_pi_worker_cmd() -> list[str]:
    return [
        sys.executable,
        str(Path(__file__).parent / "fixtures" / "fake_pi_worker_response.py"),
    ]


@pytest.fixture
def worker_stream_client(monkeypatch):
    """TestClient where the middle-man emits a dispatch and the
    worker responds to it with three progress events."""
    def cmd_for_role(role: str) -> list[str]:
        if role == "middleman":
            return _fake_pi_dispatch_cmd()
        if role == "worker":
            return _fake_pi_worker_cmd()
        raise ValueError(f"unknown role: {role!r}")

    monkeypatch.setattr(service, "pi_cmd_for_role", cmd_for_role)
    with TestClient(app) as client:
        yield client


class _Receiver:
    """Wrap a starlette WebSocketTestSession with a per-receive timeout.

    starlette 1.3.1's `WebSocketTestSession.receive_text()` blocks
    indefinitely if the server keeps the connection open but never
    sends a frame. That is exactly what happens when we wait for
    a stream the server does not (yet) produce — a hang that
    burns the whole test timeout and gives no diagnostic.

    This helper runs `receive_text` in a daemon thread and exposes
    `next_frame(timeout)` which returns one parsed JSON frame or
    raises `TimeoutError` if the server is silent for `timeout`
    seconds. A `None` sentinel is queued when the server closes
    the WS (the underlying receive raises `WebSocketDisconnect`),
    surfaced as `EOFError` so the caller can tell "closed early"
    apart from "just slow".
    """

    _EOF = object()

    def __init__(self, ws) -> None:
        self._ws = ws
        self._q: queue.Queue = queue.Queue()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            while True:
                self._q.put(self._ws.receive_text())
        except Exception:
            self._q.put(self._EOF)

    def next_frame(self, timeout: float) -> dict:
        """Block up to `timeout` seconds for the next frame.

        Raises:
            TimeoutError: server silent for `timeout` seconds.
            EOFError:     server closed the WS.
        """
        item = self._q.get(timeout=timeout)
        if item is self._EOF:
            raise EOFError("chat WebSocket closed before frame arrived")
        return json.loads(item)


def _drain(rcv: _Receiver, predicate, n: int, timeout_s: float = 5.0) -> list[dict]:
    """Receive frames until `predicate(frame)` is true `n` times.

    Middle-man and worker output both flow over the same WS,
    so we filter on the frame type we care about rather than
    counting raw frames. Uses `_Receiver.next_frame()` so a
    missing stream fails fast (TimeoutError) instead of
    hanging the test process until the global pytest timeout.
    """
    deadline = time.monotonic() + timeout_s
    out: list[dict] = []
    while len(out) < n and time.monotonic() < deadline:
        try:
            frame = rcv.next_frame(timeout=0.5)
        except TimeoutError:
            continue
        except EOFError:
            # Server closed early; let the assertion fail with
            # whatever we collected so far.
            break
        if predicate(frame):
            out.append(frame)
    return out


def test_worker_progress_streams_to_chat_ws(worker_stream_client):
    """Three worker progress events reach the chat WS as worker_line frames.

    The full chain: user_message -> middle-man dispatch ->
    worker progress events. The test asserts the WS sees
    three frames tagged `worker_line` whose inner JSON has
    `kind=edit` (the worker's progress kind in the fixture).
    """
    client = worker_stream_client
    with client.websocket_connect("/chat") as ws:
        rcv = _Receiver(ws)
        ws.send_text(
            json.dumps({"type": "user_message", "text": "build it"})
        )
        frames = _drain(
            rcv,
            predicate=lambda f: f.get("type") == "worker_line",
            n=3,
            timeout_s=5.0,
        )

    assert len(frames) == 3, f"expected 3 worker_line frames, got {frames!r}"
    for i, fr in enumerate(frames, start=1):
        inner = json.loads(fr["line"])
        assert inner["type"] == "progress"
        assert inner["kind"] == "edit"
        assert f"step {i} of 3" in inner["text"]


def test_worker_and_middleman_lines_are_distinguishable(worker_stream_client):
    """A single connection receives both middleman_line and worker_line.

    The chat UI uses the `type` field to route each frame to
    the right "speaker" (the middle-man's thought bubble vs.
    the worker's build-progress bubble). This test guards
    against a future regression where the two streams get
    conflated under a single tag.
    """
    client = worker_stream_client
    seen_types: set[str] = set()
    with client.websocket_connect("/chat") as ws:
        rcv = _Receiver(ws)
        ws.send_text(
            json.dumps({"type": "user_message", "text": "build it"})
        )
        deadline = time.monotonic() + 5.0
        # Read frames until we have seen at least one of each
        # expected type, or the deadline elapses.
        while (
            not {"middleman_line", "worker_line"}.issubset(seen_types)
            and time.monotonic() < deadline
        ):
            try:
                frame = rcv.next_frame(timeout=0.5)
            except TimeoutError:
                continue
            except EOFError:
                break
            seen_types.add(frame.get("type", ""))

    assert "middleman_line" in seen_types
    assert "worker_line" in seen_types
