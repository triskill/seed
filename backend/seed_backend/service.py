"""FastAPI orchestrator service.

Task 0.2 shipped the bare-bones service with a `/health` endpoint. Task
0.5 wires the FlaskManager into the app via a FastAPI lifespan so
`/health` reports both the orchestrator status and the Flask webapp
subprocess status. The lifespan starts Flask on app startup and stops
it on shutdown, so the two processes share the orchestrator's
lifetime.

Phase 1 added `/shell/exec`. Phase 3 (this version) wires the
`Orchestrator` (in `orchestrator.py`) into the lifespan: the
lifespan brings both `pi` runners up on startup and tears them
down on shutdown, and a WebSocket `/chat` endpoint accepts user
messages and forwards them to the middle-man. The Orchestrator
itself is defined in `orchestrator.py` (not here) to keep
`chat.py` from circular-importing this module.
"""
from __future__ import annotations

import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, WebSocket
from pydantic import BaseModel, Field

from seed_backend.chat import handle_chat
from seed_backend.flask_manager import FlaskManager
from seed_backend.orchestrator import (
    MIDDLEMAN_READ_ONLY_TOOLS,
    Orchestrator,
    pi_cmd_for_role,
    pi_env_for_role,
)
from seed_backend.pi_runner import PiRunner
from seed_backend.shell import ShellSession

# Wall-clock cap applied to /shell/exec. Prevents a runaway command
# (e.g. `sleep 999`) from tying up a uvicorn worker indefinitely.
# Task 1.4 will add client-driven cancellation on top of this server
# cap; the cap stays.
SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS: float = 60.0

_APP_URL = "http://127.0.0.1:7778"

log = logging.getLogger(__name__)


def _app_url() -> str:
    """Return the generated Flask app URL for every runtime."""
    return os.environ.get("SEED_APP_URL", _APP_URL).rstrip("/")


__all__ = [
    "app",
    "lifespan",
    "pi_cmd_for_role",
    "pi_env_for_role",
    "Orchestrator",
    "SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS",
    "ShellExecRequest",
    "ShellExecResponse",
]


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start separate Flask (:7778) and agent processes; stop them on shutdown.

    The generated app is never mounted into FastAPI.  Flask runs with its
    development reloader, so edits to the generated Python app become live
    independently of the FastAPI orchestrator on :7777.
    """
    manager = FlaskManager(port=7778)
    app.state.flask_manager = manager
    app.state.shell_session = ShellSession()
    try:
        flask_started = await manager.start()
    except Exception as exc:
        log.exception("Flask failed to start")
        raise RuntimeError("Flask failed to start") from exc
    if not flask_started:
        # Do not advertise a usable API while the generated app and worker
        # verification endpoint are unavailable. RuntimeSupervisor will see
        # the process exit and retry the whole runtime generation.
        raise RuntimeError("Flask failed to start; see /tmp/seed-flask-stderr.log")

    app_url = _app_url()
    orchestrator = Orchestrator(
        middleman=PiRunner(
            cmd=pi_cmd_for_role("middleman"),
            role="middleman",
            env=pi_env_for_role("middleman", app_url=app_url),
            read_only_tools=set(MIDDLEMAN_READ_ONLY_TOOLS),
        ),
        worker=PiRunner(
            cmd=pi_cmd_for_role("worker"),
            role="worker",
            env=pi_env_for_role("worker", app_url=app_url),
        ),
    )
    app.state.orchestrator = orchestrator
    try:
        await orchestrator.start()
    except Exception:
        log.exception("pi orchestrator failed to start")

    yield

    await orchestrator.stop()
    await manager.stop()


app = FastAPI(lifespan=lifespan)


@app.get("/health")
def health(request: Request):
    """Liveness + Flask readiness. Always 200 if the orchestrator is up."""
    manager = getattr(request.app.state, "flask_manager", None)
    flask_status = "up" if manager is not None and manager.is_up() else "down"
    return {"status": "ok", "flask": flask_status}


class ShellExecRequest(BaseModel):
    """Request body for POST /shell/exec.

    Attributes:
        command: The shell command to run. Passed to `sh -c`, so
                 shell syntax (pipes, &&, globs) is supported.
    """

    command: str = Field(..., min_length=1)


class ShellExecResponse(BaseModel):
    """Response body for POST /shell/exec.

    Mirrors `ExecResult` field-for-field so the Android client can
    deserialize it directly.
    """

    stdout: str
    stderr: str
    exit_code: int
    truncated: bool = False


@app.post("/shell/exec", response_model=ShellExecResponse)
async def shell_exec(payload: ShellExecRequest, request: Request) -> ShellExecResponse:
    """Run a shell command and return its captured output.

    Task 1.5: the route now delegates to a per-app
    `ShellSession` (created in the lifespan) instead of the
    stateless module-level `exec_command`. That gives the
    command sequence a persistent cwd across requests: a
    `cd /tmp` in one call is visible to a `pwd` in the next.
    The response shape is unchanged.
    """
    session: ShellSession = request.app.state.shell_session
    result = await session.exec(
        payload.command,
        timeout=SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS,
    )
    return ShellExecResponse(
        stdout=result.stdout,
        stderr=result.stderr,
        exit_code=result.exit_code,
        truncated=result.truncated,
    )


@app.websocket("/chat")
async def chat_endpoint(websocket: WebSocket) -> None:
    """WebSocket endpoint for the Android chat client (Task 3.2).

    Delegates to `seed_backend.chat.handle_chat`, which forwards
    user messages to the middle-man. Streaming of agent output
    is added in Tasks 3.3-3.6.

    Defensive: if the orchestrator never came up (the lifespan
    could not spawn the `pi` processes), the connection is
    closed with a 1011 (internal error) and a reason. This
    should be rare in production — the lifespan swallows spawn
    errors and leaves the orchestrator in app.state — but
    belt-and-braces here.
    """
    orchestrator = getattr(websocket.app.state, "orchestrator", None)
    if orchestrator is None:
        await websocket.close(code=1011, reason="orchestrator not initialized")
        return
    await handle_chat(websocket, orchestrator)
