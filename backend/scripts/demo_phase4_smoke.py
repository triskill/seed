#!/usr/bin/env python3
"""Phase 4 manual smoke test: orchestrator + real `pi`.

This is the "first real agent loop" test the plan calls out
as Task 4.3. It exercises the full Phase 3 + Phase 4 stack:

  1. Start the dev server in a daemon thread (uvicorn).
  2. Open a real WebSocket connection.
  3. Send a user_message.
  4. Print every event the chat stream emits.
  5. Tear down on `complete` / `app_reload` or after a
     timeout.

The user_message is intentionally a **question** (not a
build request). The middle-man prompt (`middleman.md`)
says to answer questions directly, without emitting a
dispatch JSON block. So this test exercises:

  * The orchestrator's middle-man spawn (real `pi` with
    the project-local config + the system prompt).
  * The `send_to_middleman` path.
  * The middle-man read loop streaming lines to the WS
    client.
  * The chat forwarder.

A separate test (TODO: Phase 4 follow-up) should drive a
build request end-to-end (middle-man → dispatch → worker
build → `<task:done summary="..."/>` → `complete` +
`app_reload`). That's a longer test (the worker actually
mutates `/home/seed/app/`, which is not checked out in
the worktree) and is best done with a real webapp +
real worker, which is what Phase 4 follow-up Task 4.4
("iterate on prompts") will exercise interactively.

Usage:
    export OPENCODE_API_KEY="sk-..."
    .venv/bin/python backend/scripts/demo_phase4_smoke.py
"""
from __future__ import annotations

import json
import os
import socket
import sys
import threading
import time
from pathlib import Path

import uvicorn

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
sys.path.insert(0, str(REPO_ROOT / "backend"))

from seed_backend.service import app  # noqa: E402


def _free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main() -> int:
    if not os.environ.get("OPENCODE_API_KEY"):
        print(
            "ERROR: OPENCODE_API_KEY is not set. Export it before "
            "running this script. See docs/pi-config.md.",
            file=sys.stderr,
        )
        return 2

    port = _free_port()
    host = "127.0.0.1"
    ws_url = f"ws://{host}:{port}/chat"
    print(f"[smoke] starting uvicorn on {host}:{port} ...", flush=True)

    config = uvicorn.Config(app, host=host, port=port, log_level="warning")
    server = uvicorn.Server(config)
    server_thread = threading.Thread(target=server.run, daemon=True)
    server_thread.start()

    # Wait for the lifespan to come up + spawn the pi
    # processes. uvicorn logs "Application startup complete"
    # when the lifespan finishes, but we just poll /health
    # in a tight loop.
    deadline = time.monotonic() + 15.0
    import urllib.request

    while time.monotonic() < deadline:
        try:
            with urllib.request.urlopen(
                f"http://{host}:{port}/health", timeout=0.5
            ) as r:
                body = json.loads(r.read())
                if body.get("status") == "ok":
                    break
        except Exception:
            time.sleep(0.2)
    else:
        print("[smoke] ERROR: server didn't come up in 15s", file=sys.stderr)
        server.should_exit = True
        return 1
    # Quick sanity-check before we open the WS: print the
    # resolved argv for the middle-man. Useful when
    # debugging "real pi didn't pick up the system
    # prompt" / "wrong model" — the smoke output
    # doubles as a diagnostic.
    from seed_backend.orchestrator import pi_cmd_for_role
    print("[smoke] middleman argv:")
    for arg in pi_cmd_for_role("middleman"):
        print(f"  {arg}")

    try:
        import websocket  # type: ignore
    except ImportError:
        print(
            "ERROR: websocket-client not installed; "
            "install with: pip install websocket-client",
            file=sys.stderr,
        )
        server.should_exit = True
        return 1

    ws = websocket.create_connection(ws_url, timeout=30.0)
    try:
        # Question (not a build). The middle-man should
        # answer directly, no dispatch JSON, no worker.
        prompt = "Reply with exactly one short sentence: what is your role?"
        print(f"[smoke] sending user_message: {prompt!r}", flush=True)
        ws.send(json.dumps({"type": "user_message", "text": prompt}))

        deadline = time.monotonic() + 60.0
        saw_response = False
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            ws.settimeout(max(0.1, min(5.0, remaining)))
            try:
                raw = ws.recv()
            except websocket.WebSocketTimeoutException:
                # No frame in 5s — assume the middle-man is
                # done and exit the loop. (No `complete`
                # event expected for a question, so we
                # can't wait for that.)
                break
            except websocket.WebSocketConnectionClosedException:
                break
            frame = json.loads(raw)
            t = frame.get("type", "?")
            if t == "middleman_line":
                line = frame.get("line", "")
                print(f"  [middleman] {line}", flush=True)
                saw_response = True
            elif t == "worker_line":
                print(
                    f"  [worker]    {frame.get('line', '')}",
                    flush=True,
                )
            elif t == "complete":
                print(
                    f"  [complete]  summary={frame.get('summary')!r}",
                    flush=True,
                )
                break
            elif t == "app_reload":
                print("  [app_reload] (App screen would refresh now)", flush=True)
                break
            else:
                print(f"  [?] {frame}", flush=True)

        if not saw_response:
            print(
                "[smoke] WARNING: no middleman_line received in 60s. "
                "The middle-man may have failed to start, or the API "
                "call timed out. Check the server logs above.",
                flush=True,
            )
            return 1
    finally:
        ws.close()
        server.should_exit = True
        server_thread.join(timeout=5.0)

    print("[smoke] done.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
