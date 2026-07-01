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
import time

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app

from tests._ws_helpers import (  # type: ignore[import-not-found]
    Receiver,
    collect_all,
    drain,
    filter_by_type,
    make_dispatch_worker_cmd_factory,
)


@pytest.fixture
def worker_stream_client(monkeypatch):
    """TestClient where the middle-man emits a dispatch and the
    worker responds to it with three progress events."""
    monkeypatch.setattr(
        service, "pi_cmd_for_role", make_dispatch_worker_cmd_factory()
    )
    with TestClient(app) as client:
        yield client


def test_worker_progress_streams_to_chat_ws(worker_stream_client):
    """Three worker progress events reach the chat WS as worker_line frames.

    The full chain: user_message -> middle-man dispatch ->
    worker progress events. The test asserts the WS sees
    three frames tagged `worker_line` whose inner JSON has
    `kind=edit` (the worker's progress kind in the fixture).
    """
    client = worker_stream_client
    with client.websocket_connect("/chat") as ws:
        rcv = Receiver(ws)
        ws.send_text(
            json.dumps({"type": "user_message", "text": "build it"})
        )
        frames = drain(
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
        rcv = Receiver(ws)
        ws.send_text(
            json.dumps({"type": "user_message", "text": "build it"})
        )
        deadline = time.monotonic() + 5.0
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
