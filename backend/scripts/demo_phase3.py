#!/usr/bin/env python3
"""Phase 3 manual demo: end-to-end WS /chat round-trip with fake pi.

Runs the FastAPI app on a free port with the middle-man and
worker pointed at the in-tree fake pi fixtures, opens a
WebSocket client, sends one user_message, and prints every
event the chat stream emits. Stops the server on Ctrl-C or
after the `complete` event arrives.

This is the manual verification step for Phase 3 (Task 3.7)
— the automated tests in `tests/test_complete_signal.py`
already prove the same behavior, but eyeballing the stream
is a useful checkpoint before declaring the phase done.

Usage:
    .venv/bin/python backend/scripts/demo_phase3.py

Requires the same install as the tests:
    pip install -e "./backend[dev]"
"""
from __future__ import annotations

import asyncio
import json
import socket
import sys
import threading
import time
from pathlib import Path

import uvicorn

# Path setup so `from seed_backend...` works whether you run
# this from the repo root or from backend/scripts/.
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
sys.path.insert(0, str(REPO_ROOT / "backend"))

from seed_backend import service  # noqa: E402
from seed_backend.service import app  # noqa: E402

FIXTURES = REPO_ROOT / "backend" / "tests" / "fixtures"


def _fake_pi_cmd(name: str) -> list[str]:
    return [sys.executable, str(FIXTURES / name)]


def _cmd_for_role(role: str) -> list[str]:
    """Override `pi_cmd_for_role` to use the fake fixtures.

    The middle-man uses the dispatch emitter (emits a
    fenced JSON block + done); the worker uses the
    worker-response fixture (emits 3 progress events +
    `<task:done/>`). This is the same pairing the
    end-to-end tests use.
    """
    if role == "middleman":
        return _fake_pi_cmd("fake_pi_dispatch.py")
    if role == "worker":
        return _fake_pi_cmd("fake_pi_worker_response.py")
    raise ValueError(f"unknown role: {role!r}")


def _free_port() -> int:
    """Find a free TCP port. `socket.bind(('', 0))` asks the
    kernel for one; we read it back and close."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def main() -> int:
    # Patch the role->cmd map BEFORE uvicorn imports the
    # app's lifespan. The lifespan calls
    # `pi_cmd_for_role()` to figure out what to spawn.
    service.pi_cmd_for_role = _cmd_for_role

    port = _free_port()
    host = "127.0.0.1"

    # Run uvicorn in a background thread. The thread is
    # daemonised so Ctrl-C cleanly tears down the whole
    # process even if the server hasn't fully stopped.
    config = uvicorn.Config(
        app, host=host, port=port, log_level="warning"
    )
    server = uvicorn.Server(config)
    server_thread = threading.Thread(target=server.run, daemon=True)
    server_thread.start()

    # Give uvicorn a moment to bind the socket and start the
    # lifespan (which spawns the fake pi fixtures).
    time.sleep(2.0)

    # We use the `websockets` package indirectly via the
    # `httpx`-backed test client, but for a real manual
    # demo we want a real WS client. The `websocket-client`
    # package is small and stable; if it's not installed
    # we fall back to a hand-rolled loop with the stdlib
    # `socket` module.
    ws_url = f"ws://{host}:{port}/chat"
    print(f"[demo] connecting to {ws_url} ...", flush=True)

    try:
        import websocket  # type: ignore
    except ImportError:
        print(
            "[demo] websocket-client not installed; "
            "install with: pip install websocket-client",
            file=sys.stderr,
        )
        server.should_exit = True
        server_thread.join(timeout=2.0)
        return 1

    ws = websocket.create_connection(ws_url)
    try:
        print("[demo] connected. sending user_message ...", flush=True)
        ws.send(json.dumps({"type": "user_message", "text": "build it"}))

        # Read frames until we see `complete` (or 10s elapse).
        deadline = time.monotonic() + 10.0
        while time.monotonic() < deadline:
            ws.settimeout(deadline - time.monotonic())
            try:
                raw = ws.recv()
            except websocket.WebSocketTimeoutException:
                break
            frame = json.loads(raw)
            t = frame.get("type", "?")
            if t == "middleman_line":
                line = frame.get("line", "")
                print(f"  [middleman] {line}", flush=True)
            elif t == "worker_line":
                line = frame.get("line", "")
                print(f"  [worker]    {line}", flush=True)
            elif t == "complete":
                print(
                    f"  [complete]  summary={frame.get('summary')!r}",
                    flush=True,
                )
            elif t == "app_reload":
                print("  [app_reload] (App screen would refresh now)", flush=True)
                # We saw the demo's terminal event. Break early.
                break
            else:
                print(f"  [?] {frame}", flush=True)
    finally:
        ws.close()
        server.should_exit = True
        server_thread.join(timeout=2.0)

    print("[demo] done.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
