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
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field

from seed_backend.chat import handle_chat
from seed_backend.flask_manager import FlaskManager
from seed_backend.orchestrator import (
    MIDDLEMAN_READ_ONLY_TOOLS,
    Orchestrator,
    pi_cmd_for_role,
    pi_env_for_role,
)
from seed_backend.provider_allowlist import credential_env_for
from seed_backend import pi_settings
from seed_backend.pi_control import PiControlError, PiControlService
from seed_backend.model_catalog import update_models
from seed_backend.pi_runner import PiRunner
from seed_backend.task_store import TaskStore
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


class ProviderModelsRequest(BaseModel):
    """Provider credential used only by a short-lived catalog Pi process."""

    provider: str = Field(min_length=1, max_length=100)
    api_key: str = Field(alias="apiKey", min_length=1, repr=False)

    model_config = ConfigDict(populate_by_name=True)


class ThinkingLevelsResponse(BaseModel):
    provider: str
    model_id: str = Field(alias="modelId")
    levels: list[str]

    model_config = ConfigDict(populate_by_name=True)


class SelectionRequest(BaseModel):
    provider: str = Field(min_length=1, max_length=100)
    model_id: str = Field(alias="modelId", min_length=1, max_length=300)
    thinking_level: str | None = Field(default=None, alias="thinkingLevel", max_length=20)
    model_config = ConfigDict(populate_by_name=True, extra='forbid')


class SelectionResponse(BaseModel):
    valid: bool
    model: ModelOption
    thinking_level: str = Field(alias="thinkingLevel")

    model_config = ConfigDict(populate_by_name=True)


class AgentApplyRequest(SelectionRequest):
    """A validated selection for a new Pi-agent generation."""


class AgentApplyResponse(BaseModel):
    applied: bool = True


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


def _new_control_service(
    app_url: str,
    *,
    provider: str | None = None,
) -> PiControlService:
    """Build a lazy control Pi, optionally scoped to one saved provider."""
    def runner() -> PiRunner:
        command = (
            pi_cmd_for_role("control", provider=provider)
            if provider is not None
            else pi_cmd_for_role("control")
        )
        environment = pi_env_for_role(
            "control",
            app_url=app_url,
            provider=provider,
        )
        return PiRunner(
            cmd=command,
            role="control",
            env=environment,
            read_only_tools=set(),
        )

    return PiControlService(runner_factory=runner, updater=update_models)


def _new_orchestrator(
    app_url: str,
    selection: AgentApplyRequest | None = None,
) -> Orchestrator:
    """Build the two chat agents without replacing FastAPI, Flask, or PRoot."""
    from seed_backend.pi_settings import agent_dir
    persistent_dir = agent_dir()
    def runner(role: str, *, read_only_tools: set[str] | None = None) -> PiRunner:
        if selection is None:
            command = pi_cmd_for_role(role)
            environment = pi_env_for_role(role, app_url=app_url)
        else:
            command = pi_cmd_for_role(
                role,
                provider=selection.provider,
                model=selection.model_id,
                thinking=selection.thinking_level,
            )
            environment = pi_env_for_role(
                role,
                app_url=app_url,
                provider=selection.provider,
            )
        return PiRunner(
            cmd=command,
            role=role,
            env=environment,
            read_only_tools=read_only_tools,
        )

    return Orchestrator(
        middleman=runner("middleman", read_only_tools=set(MIDDLEMAN_READ_ONLY_TOOLS)),
        worker=runner("worker"),
        task_store=TaskStore(persistent_dir),
    )


async def _replace_agents(app: FastAPI, selection: AgentApplyRequest) -> None:
    """Stop the old chat agents and start configured replacements in-place."""
    async with app.state.agent_lock:
        if getattr(app.state, 'agent_shutdown', False):
            raise RuntimeError('agent runtime is shutting down')
        previous = getattr(app.state, "orchestrator", None)
        # Persist first: a failed write must never leave new agents running with
        # the old default settings. Keep the snapshot for startup rollback.
        before = pi_settings.read_json('settings.json')
        pi_settings.save_selection(selection.provider, selection.model_id, selection.thinking_level)
        try:
            replacement = _new_orchestrator(_app_url(), selection)
        except BaseException:
            try:
                pi_settings.restore_settings(before, selection)
            except (OSError, pi_settings.ConfigConflictError):
                log.exception('could not restore Pi settings after agent construction failure')
                app.state.orchestrator = None
                if previous is not None:
                    try:
                        await previous.stop()
                    except BaseException:
                        log.exception('could not stop previous agents after settings restore failure')
            raise
        active_task = dict(previous.task_status) if previous is not None and previous._active else None
        # Do not expose the stopped generation while the replacement (or
        # rollback) is starting. In particular, a failed restart must not
        # leave a stale reference that looks usable to chat clients.
        app.state.orchestrator = None
        previous_stopped = False
        try:
            if previous is not None:
                # Drain any accepted RPC and its receipt before stopping the runner.
                # A waiter that acquired the old generation before apply will see
                # it unready after this lock is released, not send to a dead runner.
                async with previous._acceptance_lock:
                    await previous.stop()
                    previous_stopped = True
            await replacement.start()
            if not replacement.ready:
                raise RuntimeError('replacement agents are not ready')
            if previous is not None:
                replacement._subscribers = previous._subscribers
                replacement._acceptances = previous._acceptances
                replacement._acceptance_lock = previous._acceptance_lock
                if active_task is not None:
                    replacement.task_status = active_task
                    # A failed durable write must roll back before publication.
                    await replacement._status('interrupted', 'Task interrupted by model change')
        except BaseException:
            # Neither a stopped PiRunner nor a partially started generation can
            # be restarted. Preserve the triggering error across cleanup failures.
            try:
                await replacement.stop()
            except BaseException:
                log.exception('could not stop failed replacement agents')
            if previous is not None and not previous_stopped:
                try:
                    await previous.stop()  # retry incomplete teardown after a stop error
                except BaseException:
                    log.exception('could not finish stopping previous agents')
            try:
                pi_settings.restore_settings(before, selection)
            except (OSError, pi_settings.ConfigConflictError):
                log.exception('could not restore Pi settings after agent start failure')
            else:
                if previous is not None:
                    try:
                        provider = before.get('defaultProvider')
                        model = before.get('defaultModel')
                        prior = (AgentApplyRequest(provider=provider, modelId=model,
                                                  thinkingLevel=before.get('defaultThinkingLevel'))
                                 if isinstance(provider, str) and isinstance(model, str) and provider and model
                                 else None)
                        restored = _new_orchestrator(_app_url(), prior)
                        try:
                            await restored.start()
                            if not restored.ready:
                                raise RuntimeError('restored agents are not ready')
                        except BaseException:
                            try:
                                await restored.stop()
                            except BaseException:
                                log.exception('could not stop failed restored agents')
                            raise
                        restored._subscribers = previous._subscribers
                        restored._acceptances = previous._acceptances
                        restored._acceptance_lock = previous._acceptance_lock
                        if active_task is not None:
                            already_interrupted = (restored.task_status is not None
                                and restored.task_status.get('taskId') == active_task['taskId']
                                and restored.task_status.get('status') == 'interrupted')
                            if not already_interrupted:
                                restored.task_status = active_task
                                await restored._status('interrupted', 'Task interrupted by model change')
                            else:
                                # The task store already interrupted the task during
                                # restored construction. Notify inherited subscribers
                                # without writing that status a second time.
                                restored._terminal_tasks.add(active_task['taskId'])
                                await restored._broadcast(dict(restored.task_status))
                                await restored._broadcast({
                                    'type': 'task_outcome', 'taskId': active_task['taskId'],
                                    'status': 'interrupted', 'summary': 'Task interrupted',
                                    'source': 'backend',
                                })
                        app.state.orchestrator = restored
                    except BaseException:
                        log.exception('could not create previous agent generation after apply failure')
            raise
        # Publish only after task metadata is durable and subscribers notified.
        app.state.orchestrator = replacement


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
    app.state.orchestrator = None
    app.state.agent_lock = asyncio.Lock()
    app.state.agent_shutdown = False
    control_service = None
    orchestrator = None
    try:
        try:
            flask_started = await manager.start()
        except Exception as exc:
            log.exception("Flask failed to start")
            raise RuntimeError("Flask failed to start") from exc
        if not flask_started:
            # RuntimeSupervisor retries the whole generation.
            raise RuntimeError("Flask failed to start; see /tmp/seed-flask-stderr.log")

        app_url = _app_url()
        # Catalog RPC starts lazily and remains isolated from chat agents.
        control_service = _new_control_service(app_url)
        app.state.control_service = control_service
        orchestrator = _new_orchestrator(app_url)
        await orchestrator.start()
        if not orchestrator.ready:
            raise RuntimeError('pi orchestrator is not ready after startup')
        app.state.orchestrator = orchestrator
        yield
    finally:
        async with app.state.agent_lock:
            app.state.agent_shutdown = True
            current = getattr(app.state, 'orchestrator', None)
            app.state.orchestrator = None
            try:
                if current is not None:
                    await current.stop()
                if orchestrator is not None and orchestrator is not current:
                    await orchestrator.stop()
            finally:
                try:
                    if control_service is not None:
                        await control_service.stop()
                finally:
                    await manager.stop()


app = FastAPI(lifespan=lifespan)


@app.exception_handler(RequestValidationError)
async def safe_validation_error(request: Request, exc: RequestValidationError):
    if request.url.path.startswith('/control/') or request.url.path.startswith('/api/control/'):
        return JSONResponse(status_code=422, content={'detail': 'invalid control request'})
    from fastapi.exception_handlers import request_validation_exception_handler
    return await request_validation_exception_handler(request, exc)


@app.get("/health")
async def health(request: Request):
    """Report readiness only when both chat agents and Flask serve requests."""
    manager = getattr(request.app.state, "flask_manager", None)
    flask_status = "up" if manager is not None and await manager.is_ready() else "down"
    agents = getattr(request.app.state, 'orchestrator', None)
    ready = flask_status == 'up' and agents is not None and agents.ready
    return JSONResponse(
        status_code=200 if ready else 503,
        content={"status": "ok" if ready else "unavailable", "flask": flask_status},
    )


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

    The app-wide ShellSession persists the shell's final cwd and OLDPWD
    across calls, serializing requests to keep their order deterministic.
    It is shared by callers until authenticated client sessions exist.
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


@app.get('/control/v1/config')
async def control_config(request: Request):
    _require_control_access(request)
    return pi_settings.config()


@app.post('/control/v1/providers')
async def control_save_provider(payload: ProviderModelsRequest, request: Request):
    _require_control_access(request)
    try:
        pi_settings.save_provider(payload.provider, payload.api_key)
    except ValueError as exc:
        raise HTTPException(status_code=422, detail='unsupported provider') from exc
    except pi_settings.ConfigConflictError as exc:
        raise HTTPException(status_code=409, detail='Pi configuration changed; retry') from exc
    return pi_settings.config()


@app.get("/control/v1/models", response_model=ModelsResponse)
@app.get("/api/control/v1/models", response_model=ModelsResponse, include_in_schema=False)
async def control_models(request: Request, provider: str | None = None, refresh: bool = False) -> ModelsResponse:
    _require_control_access(request)
    control = request.app.state.control_service
    result = await _control_call(
        (lambda: control.get_available_models(refresh=True)) if refresh else control.get_available_models
    )
    if provider is not None:
        result = {'models': [model for model in result['models'] if model['provider'] == provider]}
    return ModelsResponse.model_validate(result)


@app.post('/control/v1/models/update')
async def control_update_models(request: Request):
    """Refresh authenticated Pi catalogs without changing settings or agents."""
    _require_control_access(request)
    return await _control_call(request.app.state.control_service.update_models)


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
    control_service = request.app.state.control_service
    result = await _control_call(
        lambda: control_service.validate_selection(
            payload.provider, payload.model_id, payload.thinking_level,
        ),
    )
    return SelectionResponse.model_validate(result)


@app.post("/control/v1/agents/apply", response_model=AgentApplyResponse)
async def control_apply_agents(
    payload: AgentApplyRequest,
    request: Request,
) -> AgentApplyResponse:
    """Apply saved settings by replacing only the two Pi chat agents."""
    _require_control_access(request)
    if not pi_settings.has_auth(payload.provider):
        raise HTTPException(status_code=422, detail="provider authentication missing")
    await _control_call(lambda: request.app.state.control_service.validate_selection(
        payload.provider, payload.model_id, payload.thinking_level))
    try:
        await _replace_agents(request.app, payload)
    except asyncio.CancelledError:
        raise
    except pi_settings.ConfigConflictError as exc:
        raise HTTPException(status_code=409, detail="Pi configuration changed; retry") from exc
    except Exception as exc:
        # Do not expose Pi diagnostics or credential-bearing environment data.
        raise HTTPException(status_code=502, detail="could not apply agent settings") from exc
    return AgentApplyResponse()


@app.websocket("/chat")
async def chat_endpoint(websocket: WebSocket) -> None:
    """WebSocket endpoint for the Android chat client (Task 3.2).

    Delegates to `seed_backend.chat.handle_chat`, which forwards
    user messages to the middle-man. Streaming of agent output
    is added in Tasks 3.3-3.6.

    If either agent becomes unavailable, reject the socket with 1011.
    """
    try:
        _require_websocket_access(websocket)
    except PermissionError:
        await websocket.close(code=1008, reason="invalid runtime capability")
        return
    orchestrator = getattr(websocket.app.state, "orchestrator", None)
    if orchestrator is None or not orchestrator.ready:
        await websocket.close(code=1011, reason="orchestrator unavailable")
        return
    await handle_chat(websocket, orchestrator, lambda: websocket.app.state.orchestrator)
