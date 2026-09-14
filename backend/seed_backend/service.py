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

import asyncio
import hmac
import logging
import os
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request, WebSocket
from pydantic import BaseModel, ConfigDict, Field

from seed_backend.chat import handle_chat
from seed_backend.flask_manager import FlaskManager
from seed_backend.orchestrator import (
    MIDDLEMAN_READ_ONLY_TOOLS,
    Orchestrator,
    pi_cmd_for_role,
    pi_env_for_role,
)
from seed_backend.pi_control import PiControlError, PiControlService
from seed_backend.pi_runner import PiRunner
from seed_backend.process_env import harden_process_visibility
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
    "ModelOption",
    "ModelsResponse",
    "ThinkingLevelsResponse",
    "SelectionRequest",
    "SelectionResponse",
]


class ModelOption(BaseModel):
    """Non-secret model metadata returned by the bundled Pi registry."""

    provider: str
    id: str
    name: str
    context_window: int | None = Field(default=None, alias="contextWindow")
    max_tokens: int | None = Field(default=None, alias="maxTokens")
    input: list[str] = Field(default_factory=list)
    supports_thinking: bool = Field(alias="supportsThinking")
    thinking_levels: list[str] = Field(alias="thinkingLevels")

    model_config = ConfigDict(populate_by_name=True)


class ModelsResponse(BaseModel):
    models: list[ModelOption]


class ThinkingLevelsResponse(BaseModel):
    provider: str
    model_id: str = Field(alias="modelId")
    levels: list[str]

    model_config = ConfigDict(populate_by_name=True)


class SelectionRequest(BaseModel):
    provider: str = Field(min_length=1, max_length=100)
    model_id: str = Field(alias="modelId", min_length=1, max_length=300)
    thinking_level: str | None = Field(default=None, alias="thinkingLevel", max_length=20)

    model_config = ConfigDict(populate_by_name=True)


class SelectionResponse(BaseModel):
    valid: bool
    model: ModelOption
    thinking_level: str = Field(alias="thinkingLevel")

    model_config = ConfigDict(populate_by_name=True)


def _require_control_access(request: Request, *, allow_development: bool = False) -> None:
    """Require loopback and the current runtime capability.

    Development TestClient instances may run without a RuntimeService-created
    capability; the Android runtime always sets one and therefore never takes
    that fallback.
    """
    peer = request.client.host if request.client is not None else None
    expected = os.environ.get("SEED_RUNTIME_CAPABILITY", "")
    if peer not in {"127.0.0.1", "::1", "localhost"}:
        if not (allow_development and not expected and peer == "testclient"):
            raise HTTPException(status_code=403, detail="control plane is loopback-only")
    supplied = request.headers.get("authorization", "")
    # Only the in-process TestClient gets a no-capability compatibility path.
    # A real loopback client must still prove possession of the runtime token.
    if not expected and allow_development and peer == "testclient":
        return
    if not expected or not hmac.compare_digest(supplied, f"Bearer {expected}"):
        raise HTTPException(status_code=401, detail="invalid runtime capability")


def _require_websocket_access(websocket: WebSocket) -> None:
    peer = websocket.client.host if websocket.client is not None else None
    expected = os.environ.get("SEED_RUNTIME_CAPABILITY", "")
    if peer not in {"127.0.0.1", "::1", "localhost"}:
        if not (not expected and peer == "testclient"):
            raise PermissionError("chat is loopback-only")
    supplied = websocket.headers.get("authorization", "")
    if expected and hmac.compare_digest(supplied, f"Bearer {expected}"):
        return
    if not expected and peer == "testclient":
        return
    raise PermissionError("invalid runtime capability")


async def _control_call(operation):
    try:
        return await operation()
    except asyncio.CancelledError:
        raise
    except PiControlError as exc:
        # Never relay Pi/provider diagnostics: they may contain credential
        # material or request headers. The client can retry without details.
        raise HTTPException(status_code=502, detail="Pi control request failed") from exc


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Start separate Flask (:7778) and agent processes; stop them on shutdown.

    The generated app is never mounted into FastAPI.  Flask runs with its
    development reloader, so edits to the generated Python app become live
    independently of the FastAPI orchestrator on :7777.
    """
    # Credentials are needed by Pi but must not be recoverable by shell/Flask
    # children through this process's /proc environment.
    harden_process_visibility()
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
    # Keep catalog/selection RPC isolated from chat's long-lived agents. The
    # service starts lazily when the protected control endpoint is requested.
    control_service = PiControlService(
        runner_factory=lambda: PiRunner(
            cmd=pi_cmd_for_role("control"),
            role="control",
            env=pi_env_for_role("control", app_url=app_url),
            read_only_tools=set(),
        ),
    )
    app.state.control_service = control_service
    orchestrator = None
    if os.environ.get("SEED_CONTROL_ONLY") != "1":
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

    if orchestrator is not None:
        await orchestrator.stop()
    await control_service.stop()
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
    _require_control_access(request, allow_development=True)
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


@app.get("/control/v1/models", response_model=ModelsResponse)
@app.get("/api/control/v1/models", response_model=ModelsResponse, include_in_schema=False)
async def control_models(request: Request) -> ModelsResponse:
    _require_control_access(request)
    result = await _control_call(request.app.state.control_service.get_available_models)
    return ModelsResponse.model_validate(result)


@app.get("/control/v1/thinking-levels", response_model=ThinkingLevelsResponse)
@app.get("/api/control/v1/thinking-levels", response_model=ThinkingLevelsResponse, include_in_schema=False)
async def control_thinking_levels(
    request: Request,
    provider: str,
    modelId: str,
) -> ThinkingLevelsResponse:
    _require_control_access(request)
    result = await _control_call(
        lambda: request.app.state.control_service.get_available_thinking_levels(provider, modelId),
    )
    return ThinkingLevelsResponse.model_validate(result)


@app.post("/control/v1/selection/validate", response_model=SelectionResponse)
@app.post("/control/v1/selection", response_model=SelectionResponse, include_in_schema=False)
@app.post("/api/control/v1/selection/validate", response_model=SelectionResponse, include_in_schema=False)
@app.post("/api/control/v1/selection", response_model=SelectionResponse, include_in_schema=False)
async def control_validate_selection(
    payload: SelectionRequest,
    request: Request,
) -> SelectionResponse:
    _require_control_access(request)
    result = await _control_call(
        lambda: request.app.state.control_service.validate_selection(
            payload.provider,
            payload.model_id,
            payload.thinking_level,
        ),
    )
    return SelectionResponse.model_validate(result)


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
    try:
        _require_websocket_access(websocket)
    except PermissionError:
        await websocket.close(code=1008, reason="invalid runtime capability")
        return
    orchestrator = getattr(websocket.app.state, "orchestrator", None)
    if orchestrator is None:
        await websocket.close(code=1011, reason="orchestrator not initialized")
        return
    await handle_chat(websocket, orchestrator)
