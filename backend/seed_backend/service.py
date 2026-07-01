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

from contextlib import asynccontextmanager

from fastapi import FastAPI, Request, WebSocket
from pydantic import BaseModel, Field

from seed_backend.chat import handle_chat
from seed_backend.flask_manager import FlaskManager
from seed_backend.orchestrator import (
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
    """Start Flask + both `pi` processes; stop them on shutdown.

    Flask is brought up first because the orchestrator's `/health`
    endpoint reports Flask status, and clients typically poll
    `/health` to know when the orchestrator is ready. The two
    `pi` processes are then spawned and their `PiRunner`s stashed
    in `app.state.orchestrator` for the chat route (Task 3.2+)
    to consume.

    If Flask fails to start (port in use, webapp not importable,
    etc.) the orchestrator still comes up — `/health` will
    report `flask: "down"` so the caller can diagnose.
    Crashing the whole orchestrator because the webapp failed
    to bind would be worse.

    If the `pi` cmd is unrunnable (e.g. `pi` not installed yet,
    pre-Phase 4) the orchestrator still comes up. The lifespan
    swallows the spawn failure and leaves the orchestrator in
    `app.state` so a later restart (or a `pip install
    pi-coding-agent`) can bring it up without restarting uvicorn.
    In that case, `orchestrator.middleman.pid` is set (the fork
    succeeded) but the child immediately exited; the runner
    surfaces that on first `send()`.

    Also creates a single `ShellSession` on `app.state` so every
    `/shell/exec` call shares the same cwd. Task 1.5: the
    session is process-global by design for v0.1 — one logical
    shell per orchestrator process. A future task may scope
    sessions per client.
    """
    manager = FlaskManager(port=7778)
    app.state.flask_manager = manager
    app.state.shell_session = ShellSession()
    try:
        await manager.start()
        await manager.wait_ready(timeout=15)
    except Exception:
        # Flask didn't come up; manager.is_up() returns False so
        # /health will surface the failure. Move on.
        pass

    # Phase 3: bring up both `pi` runners. (Task 3.1)
    # The env passed to each runner overrides
    # `PI_CODING_AGENT_DIR` to the project's local config
    # (so the agent uses our defaultProvider/defaultModel
    # from `.pi/agent/settings.json`) while still
    # inheriting API keys set in the parent shell.
    orchestrator = Orchestrator(
        middleman=PiRunner(
            cmd=pi_cmd_for_role("middleman"),
            role="middleman",
            env=pi_env_for_role("middleman"),
        ),
        worker=PiRunner(
            cmd=pi_cmd_for_role("worker"),
            role="worker",
            env=pi_env_for_role("worker"),
        ),
    )
    app.state.orchestrator = orchestrator
    try:
        await orchestrator.start()
    except Exception:
        # Pi failed to spawn (likely `pi` not installed). Leave
        # the orchestrator in app.state — /health still works,
        # /chat will surface the failure on first use.
        pass

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
