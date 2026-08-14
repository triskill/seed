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
from pathlib import Path

from fastapi import FastAPI, Request, WebSocket
from pydantic import BaseModel, Field

from seed_backend.chat import handle_chat
from seed_backend.config import Config, DEFAULT_PORTS
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

_HOST_APP_URL = "http://127.0.0.1:7778"
_EMBEDDED_APP_URL = "http://127.0.0.1:7777"

log = logging.getLogger(__name__)


def _app_url_for_mode(*, flask_subprocess_running: bool) -> str:
    """Return the webapp URL exposed by the active Flask mode.

    Host development runs Flask separately on port 7778. Android PRoot
    cannot spawn that process, so Flask is WSGI-mounted into FastAPI on
    port 7777. An explicit SEED_APP_URL remains available for custom
    development layouts.
    """
    configured = os.environ.get("SEED_APP_URL")
    if configured:
        return configured.rstrip("/")
    return _HOST_APP_URL if flask_subprocess_running else _EMBEDDED_APP_URL


# Where the orchestrator reads / writes its
# config.json. The Android Settings screen
# (Phase 6.5) PUTs the user's settings to
# `PUT /config` and the route persists them
# to this path. The next orchestrator start
# will read the file in a future task (the
# orchestrator currently reads API keys
# from environment variables; the config
# file is the v0.2+ mechanism).
#
# v0.1 keeps the path hardcoded relative to
# the uvicorn CWD. The Makefile's `dev.sh`
# script `cd`s into `backend/` before
# starting uvicorn, so `./config.json`
# resolves to `backend/config.json` in
# dev. A future task may make this
# configurable via an env var
# (`SEED_CONFIG_PATH`) so production
# deployments can put it wherever they want.
DEFAULT_CONFIG_PATH: Path = Path("config.json")


__all__ = [
    "app",
    "lifespan",
    "pi_cmd_for_role",
    "pi_env_for_role",
    "Orchestrator",
    "SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS",
    "DEFAULT_CONFIG_PATH",
    "ShellExecRequest",
    "ShellExecResponse",
    "ConfigPayload",
    "ConfigResponse",
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

    Flask starts in one of two modes:

      * **subprocess** (dev): FlaskManager spawns `flask --app
        seed_app.app run` as a child process. FLASK_DEBUG=1
        enables the Werkzeug reloader so the worker agent's
        edits to app.py are picked up on the next request.
      * **wsgi_mount** (embedded runtime fallback): the
        embedded Linux runtime launches the orchestrator
        inside proot, which does not implement `fork(2)` on
        Android. The lifespan catches the spawn failure and
        mounts the Flask app's `wsgi_app` inside the
        FastAPI process via a2wsgi — same routes, no
        subprocess, no reloader.

    The orchestrator still comes up regardless of Flask mode
    — `/health` reports `flask: "up"` in either mode, or
    `flask: "down"` if both fail (e.g. `seed_app` not
    installed).

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

    # Try subprocess first (dev path); fall back to WSGI mount
    # (embedded-runtime path). Either way, /health will report
    # `flask: "up"` once the routes are reachable.
    subprocess_ok = False
    try:
        subprocess_ok = await manager.start()
    except Exception:
        subprocess_ok = False

    if not subprocess_ok:
        # Mount the Flask WSGI app inside the FastAPI process
        # via a2wsgi. No subprocess, no reloader, but the same
        # routes serve on `/`. Imports happen here (not at
        # module top) so a missing webapp package (e.g. partial
        # extraction) doesn't prevent the orchestrator from
        # starting at all.
        try:
            from a2wsgi import WSGIMiddleware  # type: ignore[import-not-found]
            from seed_app.app import app as flask_app  # type: ignore[import-not-found]

            # Flask is itself a WSGI callable — pass it directly,
            # not `flask_app.wsgi_app` (the WSGI middleware Flask
            # provides for nested WSGI apps).
            #
            # NB: `from seed_app import app` would import the
            # *module* `seed_app.app` (because Python prefers the
            # submodule over a top-level attribute named `app`),
            # and a module isn't callable as a WSGI app. The
            # explicit `from seed_app.app import app` reaches the
            # Flask instance defined in app.py.
            app.mount("/", WSGIMiddleware(flask_app))
            manager.mount_wsgi()
            print("[lifespan] Flask mounted via WSGI in-process (subprocess mode unavailable)", flush=True)
        except Exception as exc:
            # Both modes failed; /health will surface `flask: "down"`.
            print(f"[lifespan] Flask WSGI mount failed: {exc!r}", flush=True)
            with open("/tmp/seed-flask-wsgi-error.log", "w") as fh:
                fh.write(f"WSGI mount failed: {exc!r}\n")

    # Phase 3: bring up both `pi` runners. (Task 3.1)
    # The env passed to each runner overrides
    # `PI_CODING_AGENT_DIR` to the project's local config
    # (so the agent uses our defaultProvider/defaultModel
    # from `.pi/agent/settings.json`) while still
    # inheriting API keys set in the parent shell.
    app_url = _app_url_for_mode(flask_subprocess_running=subprocess_ok)
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
        # Pi failed to spawn (likely `pi` not installed). Leave
        # the orchestrator in app.state — /health still works,
        # /chat will surface the failure on first use.
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


class ConfigPayload(BaseModel):
    """Request body for `PUT /config` (Phase 6.5).

    Mirrors the Android `data.ConfigRequest` DTO field-for-field
    so a `PUT /config` from the Settings screen lands directly
    in the on-disk `config.json` (via [seed_backend.config.Config.save]).

    The `ports` sub-object matches the backend's
    [seed_backend.config.DEFAULT_PORTS] dict: `backend` (FastAPI,
    default 7777) and `flask` (webapp, default 7778). The Android
    side maps its `SettingsForm.backendPort` → `ports.backend`
    and `SettingsForm.webappPort` → `ports.flask` in
    `ConfigSync.toRequest`.

    **Why a `ports` sub-object (not two top-level fields):**
    the on-disk format already uses a `ports` dict (the
    dataclass field is `ports: dict[str, int]`), so PUTting a
    flat shape would force the route to flatten on write and
    the next reader to re-nest. Keeping the wire shape the
    same as the file shape is the smallest delta.

    **Why no `logLevel` here:** the Android Settings form has
    a `logLevel` field (Phase 5.6) but the orchestrator has no
    concept of log level yet (Phase 7+ will add a `RuntimeService`
    log view). The Android side doesn't send `logLevel` to the
    backend — it stays a client-side concern. The
    `ConfigSync.toRequest` deliberately drops the field.
    """

    provider: str = Field(..., min_length=1)
    model: str = Field(..., min_length=1)
    api_key: str = ""
    ports: dict[str, int] = Field(default_factory=lambda: dict(DEFAULT_PORTS))


class ConfigResponse(BaseModel):
    """Response body for `PUT /config`.

    A small ack: `{"ok": true}` on success. The Android
    [com.seed.app.data.ConfigSync] checks [ok] and surfaces a
    "sync failed" error in the Settings UI if it's `false` (a
    future task may add a banner; for Phase 6.5 we just log
    the failure and let the local save stand).
    """

    ok: bool


@app.put("/config", response_model=ConfigResponse)
async def put_config(payload: ConfigPayload) -> ConfigResponse:
    """Persist the user's settings to [DEFAULT_CONFIG_PATH].

    Phase 6.5 wires the Android Settings screen to this route
    via [com.seed.app.data.ConfigSync]. The flow is:
      1. User taps Save on the Settings screen.
      2. Android's `SettingsViewModel.save()` calls
         `SettingsRepo.save(form)` (local persistence to
         DataStore + EncryptedSharedPreferences) and then
         `ConfigSync.sync(form)` (this endpoint).
      3. The route builds a [Config] from [payload] and
         writes it via `Config.save(DEFAULT_CONFIG_PATH)`.
      4. On the next orchestrator start (Phase 7+ will wire
         this), the file is read back via
         `Config.load(DEFAULT_CONFIG_PATH)`.

    **Defensive — 422 on missing fields:** the `Field(..., min_length=1)`
    on `provider` and `model` matches the Android
    `SettingsForm.DEFAULTS` (which always populates both), but a
    hand-rolled curl call could send `{"provider": ""}` and get
    a 422. The Android side never does that.
    """
    cfg = Config(
        provider=payload.provider,
        model=payload.model,
        api_key=payload.api_key,
        ports=payload.ports,
    )
    cfg.save(DEFAULT_CONFIG_PATH)
    return ConfigResponse(ok=True)
