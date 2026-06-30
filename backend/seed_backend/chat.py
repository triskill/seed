"""WebSocket /chat endpoint (Task 3.2).

Bridges the Android chat client to the Orchestrator's two `pi`
agents. Task 3.2 covers the request half: accept the WebSocket,
receive a user_message frame, forward it to the middle-man.
Streaming of agent output back to the client is added in
Tasks 3.3 (middle-man) and 3.5 (worker); complete + app_reload
events are added in Task 3.6.

The wire format is JSON, one object per WS text frame:

  client -> server:
    {"type": "user_message", "text": "..."}

  server -> client:
    (Tasks 3.3-3.6 add line / complete / app_reload / error
    events.)

Unknown message types and malformed JSON are logged and
dropped rather than closing the connection: a chat client
that gets a version skew (a new field, a renamed type) should
keep working, not get a hard close. The connection is closed
only on a real WebSocketDisconnect from the peer.

This module is intentionally small: the only orchestration
logic is "forward user_message to the middle-man". Tasks
3.3+ plug a reader loop into the orchestrator that uses the
`Orchestrator.subscribe()` queue API (defined in Task 3.1) to
fan out events.
"""
from __future__ import annotations

import asyncio
import json
import logging
from typing import Any

from fastapi import WebSocket, WebSocketDisconnect

from seed_backend.orchestrator import Orchestrator
from seed_backend.pi_runner import PiRunnerNotRunning

log = logging.getLogger(__name__)


async def handle_chat(
    websocket: WebSocket, orchestrator: Orchestrator
) -> None:
    """Handle one WebSocket connection.

    Accepts the upgrade, subscribes a private queue to the
    orchestrator, and spawns a forwarder task that pumps
    orchestrator events (middle-man lines, worker lines,
    complete, app_reload, errors) to the WebSocket as JSON
    text frames. Then loops on `receive_text` for inbound
    user messages.

    The forwarder is a peer task: it runs concurrently with
    the inbound loop. Both are torn down on disconnect
    (WebSocketDisconnect from the inbound loop, or a `None`
    sentinel from the forwarder when the subscriber queue
    drains after unsubscribe).

    Args:
        websocket:    The Starlette/FastAPI WebSocket.
        orchestrator: The Orchestrator instance from
                      `app.state.orchestrator`. Must be
                      non-None; the route layer checks
                      that.

    Returns:
        None. Returns on `WebSocketDisconnect` (the client
        closed the connection) or on any other terminal
        condition.
    """
    await websocket.accept()
    log.info("chat: client connected")
    subscriber_queue = orchestrator.subscribe()
    forwarder_task = asyncio.create_task(
        _forward_events(websocket, subscriber_queue),
        name="chat-forwarder",
    )
    try:
        try:
            async for raw in _iter_text_frames(websocket):
                if not raw:
                    # Empty frame: ignore.
                    continue
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    log.warning(
                        "chat: dropping non-JSON frame: %r", raw[:100]
                    )
                    continue
                if not isinstance(msg, dict):
                    log.warning(
                        "chat: dropping non-object frame: %r", raw[:100]
                    )
                    continue
                msg_type = msg.get("type")
                if msg_type == "user_message":
                    await _handle_user_message(websocket, orchestrator, msg)
                else:
                    log.warning("chat: unknown message type: %r", msg_type)
        except WebSocketDisconnect:
            log.info("chat: client disconnected")
    finally:
        # Cancel the forwarder and unsubscribe in either order:
        # the forwarder's `await q.get()` will return
        # immediately on cancel, and the unsubscribe
        # is idempotent. Doing it in `finally` makes the
        # cleanup path robust to early returns.
        forwarder_task.cancel()
        try:
            await forwarder_task
        except (asyncio.CancelledError, Exception):
            pass
        orchestrator.unsubscribe(subscriber_queue)
        log.info("chat: client torn down")


async def _iter_text_frames(websocket: WebSocket):
    """Yield text frames from the WebSocket until it disconnects.

    Starlette raises `WebSocketDisconnect` from
    `receive_text()` on close; we let it propagate so the
    caller's `async for` loop can `except` it and exit.
    Empty frames are not yielded (the caller skips them
    via the `if not raw` check, but we still pass them
    through for symmetry with the real protocol).
    """
    while True:
        yield await websocket.receive_text()


async def _forward_events(
    websocket: WebSocket, queue: asyncio.Queue
) -> None:
    """Pump orchestrator events to the WebSocket as JSON text frames.

    Runs as a long-lived task alongside the inbound loop in
    `handle_chat`. Each `dict` placed on the queue by
    `Orchestrator._broadcast` (Task 3.3+) is JSON-serialised
    and sent as a text frame. The forwarder exits when:

      * the orchestrator publishes a `None` sentinel (not
        used in 3.3; reserved for Task 3.6's `complete`
        handling, if we ever want to close the WS server-
        side);
      * the task is cancelled (the chat handler's `finally`
        block does this on disconnect);
      * `send_text` raises (the WS is dead).

    Args:
        websocket: The Starlette WebSocket to write frames to.
        queue:     The orchestrator subscriber queue registered
                   by `handle_chat`.
    """
    while True:
        event = await queue.get()
        if event is None:
            return
        try:
            await websocket.send_text(json.dumps(event))
        except Exception as exc:
            log.warning("chat: forwarder send failed: %r", exc)
            return


async def _handle_user_message(
    websocket: WebSocket,
    orchestrator: Orchestrator,
    msg: dict[str, Any],
) -> None:
    """Forward a validated user_message to the middle-man.

    Defensive checks:
      * `text` must be a string. Non-strings (numbers,
        nulls, lists) are dropped with a warning.
      * If the orchestrator is in a broken state
        (e.g. middleman never started because `pi`
        isn't installed), `send_to_middleman` raises
        `PiRunnerNotRunning`; we catch and reply with
        an `error` frame so the client can show a
        useful message rather than just hanging.

    Args:
        websocket:    The WS to optionally send an error
                      reply on.
        orchestrator: The Orchestrator (forwarded to).
        msg:          The decoded user_message dict.
    """
    text = msg.get("text", "")
    if not isinstance(text, str):
        log.warning("chat: user_message.text is not a string: %r", text)
        return
    try:
        await orchestrator.send_to_middleman(text)
    except PiRunnerNotRunning as exc:
        log.warning("chat: middleman not running: %s", exc)
        try:
            await websocket.send_text(
                json.dumps(
                    {
                        "type": "error",
                        "message": "orchestrator not running",
                    }
                )
            )
        except Exception:
            # Connection is gone or broken; nothing to do.
            pass
