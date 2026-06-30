"""FastAPI orchestrator service.

Task 0.2 shipped the bare-bones service with a `/health` endpoint. Task
0.5 wires the FlaskManager into the app via a FastAPI lifespan so
`/health` reports both the orchestrator status and the Flask webapp
subprocess status. The lifespan starts Flask on app startup and stops
it on shutdown, so the two processes share the orchestrator's
lifetime.

Phase 1 added `/shell/exec`. Phase 3 (this version) adds the
`Orchestrator`: a small class that owns the middle-man and worker
`PiRunner` subprocesses and exposes a tiny pub-sub interface that the
WebSocket `/chat` route (Task 3.2) plugs into. The lifespan brings
both runners up on startup and tears them down on shutdown, so the
two pi processes share the orchestrator's lifetime.
"""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from pydantic import BaseModel, Field

from seed_backend.flask_manager import FlaskManager
from seed_backend.pi_runner import PiRunner
from seed_backend.shell import ShellSession

# Wall-clock cap applied to /shell/exec. Prevents a runaway command
# (e.g. `sleep 999`) from tying up a uvicorn worker indefinitely.
# Task 1.4 will add client-driven cancellation on top of this server
# cap; the cap stays.
SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS: float = 60.0


def pi_cmd_for_role(role: str) -> list[str]:
    """Return the argv used to spawn the `pi` CLI for a given role.

    Production default: real `pi` in RPC mode (TBD flags — Phase 4
    will pin the exact invocation). Tests monkey-patch this to
    return the in-tree `fake_pi.py` fixture so the orchestrator can
    be exercised without an LLM or a `pi` install.

    Args:
        role: "middleman" or "worker". Any other value raises
              ValueError so a typo in the caller fails fast.

    Returns:
        The argv list to pass to `PiRunner` / `os.execvp`.

    Raises:
        ValueError: if `role` is not one of the known roles.
    """
    if role not in ("middleman", "worker"):
        raise ValueError(f"unknown pi role: {role!r}")
    return ["pi", "--mode", "rpc", "--role", role]


class Orchestrator:
    """Owns the two `pi` subprocesses and routes events to chat clients.

    Task 3.1: the orchestrator is a thin container for the
    middle-man and worker `PiRunner`s. It brings both up on
    `start()` and tears both down on `stop()`. Tasks 3.2-3.6
    add:

      * a per-subscriber event queue (`subscribe` / `unsubscribe`)
        that the WebSocket route consumes;
      * background read loops that shovel middle-man and worker
        output into those queues;
      * dispatch-JSON detection (middle-man -> worker);
      * a `complete` + `app_reload` broadcast on worker done.

    The pub-sub surface is in place from Task 3.1 so the WS
    route (3.2) can register its queue without further changes
    to the orchestrator.

    Attributes:
        middleman: The `PiRunner` driving the intent agent.
        worker:    The `PiRunner` driving the builder agent.
    """

    # Cap on the per-subscriber queue. Slow clients drop events
    # rather than backpressure the reader tasks; the chat UI
    # would rather see a gap than freeze. 256 is a comfortable
    # headroom for the 3-5 events a typical turn emits.
    _SUBSCRIBER_QUEUE_MAXSIZE: int = 256

    def __init__(self, middleman: PiRunner, worker: PiRunner) -> None:
        self.middleman = middleman
        self.worker = worker
        # Each chat WS client subscribes by calling subscribe();
        # the orchestrator hands them a private queue and tracks
        # it in this set for broadcast. The set itself is mutated
        # only from the asyncio thread (no extra lock needed).
        self._subscribers: set[asyncio.Queue[dict]] = set()

    async def start(self) -> None:
        """Spawn both `pi` processes. No-op if already started.

        Each `PiRunner.start()` does its own fork+exec, so this
        call returns as soon as both children are running (or
        have already failed to exec — the runner keeps the pid
        in that case so `stop()` can still reap the child).
        """
        await self.middleman.start()
        await self.worker.start()

    async def stop(self) -> None:
        """Stop both runners. Idempotent; safe to call on a
        half-started orchestrator (the runners' own start() is
        idempotent and stop() is too)."""
        await self.middleman.stop()
        await self.worker.stop()

    def subscribe(self) -> asyncio.Queue[dict]:
        """Register a new chat client. Returns a private queue
        the orchestrator will publish events to.

        The caller (the WS route) is responsible for
        `unsubscribe(queue)` on disconnect. The queue is bounded;
        if the caller is slow, the orchestrator drops events for
        that client rather than block the reader tasks.
        """
        q: asyncio.Queue[dict] = asyncio.Queue(
            maxsize=self._SUBSCRIBER_QUEUE_MAXSIZE
        )
        self._subscribers.add(q)
        return q

    def unsubscribe(self, queue: asyncio.Queue[dict]) -> None:
        """Remove a subscriber. Idempotent; unknown queues are
        silently ignored."""
        self._subscribers.discard(queue)

    async def _broadcast(self, event: dict) -> None:
        """Publish an event to every subscriber queue. Drops for
        slow consumers (no blocking, no backpressure).

        Used by the middle-man and worker read loops (Tasks 3.3
        and 3.5) to shovel lines to chat clients, and by the
        complete-signal handler (Task 3.6) to fan out the
        `app_reload` event. The list() copy avoids "set changed
        during iteration" if a subscribe/unsubscribe races the
        broadcast.
        """
        for q in list(self._subscribers):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                # Drop for slow consumer. The chat client will
                # see a gap; the reader keeps moving.
                pass

    async def send_to_middleman(self, message: str) -> None:
        """Forward a user message to the middle-man.

        The WS route (Task 3.2) calls this when a user hits
        "send" in the chat UI. The middle-man then thinks, asks
        a clarifying question, or emits a dispatch JSON block
        that the orchestrator forwards to the worker (Task 3.4).
        """
        await self.middleman.send(message)


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
    orchestrator = Orchestrator(
        middleman=PiRunner(
            cmd=pi_cmd_for_role("middleman"), role="middleman"
        ),
        worker=PiRunner(
            cmd=pi_cmd_for_role("worker"), role="worker"
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
