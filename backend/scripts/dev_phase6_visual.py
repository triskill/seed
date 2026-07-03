#!/usr/bin/env python3
"""Phase 6 visual-verification backend.

Starts the FastAPI app on 127.0.0.1:7777 (the dev port the
Android client expects) with the middle-man + worker pointed
at the in-tree fake pi fixtures, so the chat works without
an OPENCODE_API_KEY.

The Android client (Phase 6) reads `BuildConfig.BACKEND_DEV_URL`
which is `http://10.0.2.2:7777/` on the emulator. From the
host's perspective, that's `127.0.0.1:7777` — so this script
binds 127.0.0.1:7777 and the emulator's 10.0.2.2 alias
resolves to it.

This is the same pairing the Phase 3 demo uses
(`fake_pi_dispatch.py` for the middle-man, `fake_pi_worker_response.py`
for the worker). The middle-man emits a dispatch JSON block;
the worker emits 3 progress events + `<task:done/>`. The
Android chat UI sees the stream end-to-end.

Run in the background and tail the log to see the events:

    .venv/bin/python backend/scripts/dev_phase6_visual.py \
        > backend_phase6.log 2>&1 &
    echo $! > backend_phase6.pid
    # ... emulator + adb work ...
    kill $(cat backend_phase6.pid)
"""
from __future__ import annotations

import sys
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
HOST = "127.0.0.1"
PORT = 7777  # the dev port the Android client targets


def _fake_pi_cmd(name: str) -> list[str]:
    return [sys.executable, str(FIXTURES / name)]


def _cmd_for_role(role: str) -> list[str]:
    """Override `pi_cmd_for_role` to use the fake fixtures.

    The middle-man uses the dispatch emitter (emits a
    fenced JSON block + done); the worker uses the
    worker-response fixture (emits 3 progress events +
    `<task:done/>`). This is the same pairing the
    end-to-end tests use (see demo_phase3.py).
    """
    if role == "middleman":
        return _fake_pi_cmd("fake_pi_dispatch.py")
    if role == "worker":
        return _fake_pi_cmd("fake_pi_worker_response.py")
    raise ValueError(f"unknown role: {role!r}")


def main() -> int:
    # Patch the role->cmd map BEFORE uvicorn imports the
    # app's lifespan. The lifespan calls
    # `pi_cmd_for_role()` to figure out what to spawn.
    service.pi_cmd_for_role = _cmd_for_role

    config = uvicorn.Config(
        app, host=HOST, port=PORT, log_level="info"
    )
    server = uvicorn.Server(config)
    print(
        f"[dev_phase6] starting uvicorn on {HOST}:{PORT} "
        f"(fake pi fixtures, no API key needed) ...",
        flush=True,
    )
    # Blocking call — the script runs until the server
    # is stopped (Ctrl-C or SIGTERM). The Android client
    # connects to ws://10.0.2.2:7777/chat (or
    # http://10.0.2.2:7777/ for the REST endpoints).
    server.run()
    return 0


if __name__ == "__main__":
    sys.exit(main())
