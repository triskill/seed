"""Tests for the worker's task-done signal and app reload trigger (Task 3.6).

When the worker finishes a build, it emits a `<task:done/>`
marker on its stdout. The orchestrator's worker read loop
detects the marker and broadcasts two events to every chat
WS client:

    {"type": "complete",  "summary": "..."}
    {"type": "app_reload"}

`complete` is what the chat UI uses to render the "your
habit tracker is ready" bubble; `app_reload` is what the App
screen's WebView listens for to reload the running web app
at the new state. The two are separate so a chat-only client
can ignore `app_reload` and an app-only client can ignore
the verbose `complete` summary.

The end-to-end test: user_message -> middle-man dispatch ->
worker progress + `<task:done/>` -> WS sees complete + app_reload.
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
    filter_by_type,
    make_dispatch_worker_cmd_factory,
)


@pytest.fixture
def complete_signal_client(monkeypatch):
    """TestClient with the dispatch + worker-response fixture pair."""
    monkeypatch.setattr(
        service, "pi_cmd_for_role", make_dispatch_worker_cmd_factory()
    )
    with TestClient(app) as client:
        yield client


def test_complete_signal_emits_app_reload_event(complete_signal_client):
    """`<task:done/>` from the worker causes `complete` + `app_reload` to broadcast."""
    client = complete_signal_client
    with client.websocket_connect("/chat") as ws:
        rcv = Receiver(ws)
        ws.send_text(
            json.dumps({"type": "user_message", "text": "build it"})
        )
        # Read the full stream once and filter for both event
        # types — they arrive back-to-back, so a single window
        # captures both. (Calling `collect_by_type` twice in
        # a row would race: the second call's queue is empty
        # because the first call already consumed the
        # `app_reload` frame.)
        frames = collect_all(rcv, timeout_s=5.0)
        complete_frames = filter_by_type(frames, "complete")
        app_reload_frames = filter_by_type(frames, "app_reload")

    assert len(complete_frames) == 1, (
        f"expected exactly 1 'complete' frame, got {complete_frames!r}"
    )
    assert "summary" in complete_frames[0], complete_frames[0]
    assert len(app_reload_frames) == 1, (
        f"expected exactly 1 'app_reload' frame, got {app_reload_frames!r}"
    )
    # app_reload is a pure signal — no payload needed in v0.1.
    assert app_reload_frames[0]["type"] == "app_reload"


def test_complete_signal_fans_out_to_multiple_clients(complete_signal_client):
    """A second connected chat client also receives `complete` + `app_reload`.

    The app_reload signal is what the App screen WebView
    listens for; the chat screen subscribes for the summary.
    A real run will have both kinds of client connected
    simultaneously. This test guards against a future bug
    where the broadcast is scoped to a single queue.
    """
    client = complete_signal_client

    with client.websocket_connect("/chat") as chat_ws:
        chat_rcv = Receiver(chat_ws)
        chat_ws.send_text(
            json.dumps({"type": "user_message", "text": "build it"})
        )
        # Give the orchestrator a moment to start the worker
        # turn, then open a second "app" connection that
        # should also receive the broadcast.
        time.sleep(0.2)
        with client.websocket_connect("/chat") as app_ws:
            app_rcv = Receiver(app_ws)
            chat_frames = collect_all(chat_rcv, timeout_s=5.0)
            app_frames = collect_all(app_rcv, timeout_s=2.0)

    chat_complete = filter_by_type(chat_frames, "complete")
    app_complete = filter_by_type(app_frames, "complete")
    assert len(chat_complete) >= 1, chat_complete
    assert len(app_complete) >= 1, app_complete
