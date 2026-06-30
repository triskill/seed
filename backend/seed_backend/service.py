"""FastAPI orchestrator service.

Task 0.2 shipped the bare-bones service with a `/health` endpoint. Task
0.5 wires the FlaskManager into the app via a FastAPI lifespan so
`/health` reports both the orchestrator status and the Flask webapp
subprocess status. The lifespan starts Flask on app startup and stops
it on shutdown, so the two processes share the orchestrator's
lifetime. Phase 1+ will add the shell, WebSocket chat, and pi runner
on top of this skeleton.
"""
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from pydantic import BaseModel

from seed_backend.flask_manager import FlaskManager
from seed_backend.shell import exec_command


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start Flask on app startup; stop it on shutdown.

    If Flask fails to start (port in use, webapp not importable, etc.)
    the orchestrator still comes up — `/health` will report
    `flask: "down"` so the caller can diagnose. Crashing the whole
    orchestrator because the webapp failed to bind would be worse.
    """
    manager = FlaskManager(port=7778)
    app.state.flask_manager = manager
    try:
        await manager.start()
        await manager.wait_ready(timeout=15)
    except Exception:
        # Flask didn't come up; manager.is_up() returns False so
        # /health will surface the failure. Move on.
        pass
    yield
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

    command: str


class ShellExecResponse(BaseModel):
    """Response body for POST /shell/exec.

    Mirrors `ExecResult` field-for-field so the Android client can
    deserialize it directly.
    """

    stdout: str
    stderr: str
    exit_code: int


@app.post("/shell/exec", response_model=ShellExecResponse)
async def shell_exec(payload: ShellExecRequest) -> ShellExecResponse:
    """Run a shell command and return its captured output.

    The actual subprocess management lives in `shell.exec_command` —
    this route is just a thin adapter: validate the request, call
    `exec_command`, and shape the result into the response.
    """
    result = await exec_command(payload.command)
    return ShellExecResponse(
        stdout=result.stdout,
        stderr=result.stderr,
        exit_code=result.exit_code,
    )
