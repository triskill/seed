"""Orchestrator: the two-pi agent container (Task 3.1+).

The orchestrator owns the middle-man and worker `PiRunner`
subprocesses and exposes a tiny pub-sub interface that the
WebSocket `/chat` route consumes. It is a thin container; the
`PiRunner`s do the pipe-backed process + read-loop work, and the route
layer (in `chat.py`) is what streams events back to clients.

Why is this in its own module? Both `service.py` (which
defines the `/chat` WebSocket route) and `chat.py` (which
implements the handler) need to refer to `Orchestrator`.
Keeping it in `service.py` would force `chat.py` to import
`service.py` and create a circular import. A dedicated
module breaks the cycle without any `TYPE_CHECKING` tricks.

Lifecycle (Task 3.1):
    orch = Orchestrator(middleman=..., worker=...)
    await orch.start()      # forks + execs both children
    await orch.stop()       # SIGTERMs + reaps both children

Pub-sub surface (Task 3.1+, used by Task 3.2+):
    q = orch.subscribe()            # chat client registers
    await orch.send_to_middleman(t) # user message -> middleman
    await orch._broadcast(event)    # read loops -> subscribers
    orch.unsubscribe(q)             # on disconnect

Background read loops (Tasks 3.3, 3.5) and dispatch JSON
forwarding (Task 3.4) are added on top of this skeleton.
"""
from __future__ import annotations

import asyncio
import json
import logging
import os
import time
import uuid
from pathlib import Path

from seed_backend.events import (
    parse_task_done,
    TASK_PROGRESS_RE,
    translate_pi_line,
    WS_TYPE_COMPLETE,
    WS_TYPE_MIDDLEMAN_LINE,
    WS_TYPE_WORKER_LINE,
)
from seed_backend.middleman import extract_dispatch
from seed_backend.pi_runner import PiRunner
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS, SEED_CAPABILITY_ENV
from seed_backend.provider_allowlist import credential_env_for
from seed_backend.pi_settings import agent_dir
from seed_backend.task_store import TaskStore

log = logging.getLogger(__name__)

# Cap on the middle-man scan buffer. A typical dispatch body
# is a few hundred bytes; 64 KiB is comfortable headroom for
# unusually long specs while still bounding memory in case
# the agent never closes the block.
_MIDDLEMAN_SCAN_BUFFER_MAX = 64 * 1024

# v0.1 placeholder summary text for the `complete` event.
# Used when the worker emits a bare `<task:done/>` (no
# `summary` attribute). In Phase 4 the worker prompt
# (`backend/prompts/worker.md`) tells the agent to emit
# `<task:done summary="..."/>` — when it does, that
# string is what the chat UI shows, and this placeholder
# is never used. Keeping the constant so a worker that
# forgets the summary attribute still produces a usable
# event.
_DEFAULT_COMPLETE_SUMMARY = "Task complete"

# Project-local pi config directory. pi's `--config-dir`
# (env: `PI_CODING_AGENT_DIR`) defaults to `~/.pi/agent`.
# We override it to a path inside the repo so the project's
# agent runs use our defaults (`opencode-go` /
# `deepseek-v4-flash`) instead of the user's global config.
#
# The path is resolved relative to this source file:
# `seed_backend/orchestrator.py` -> `backend/seed_backend/`
# -> `backend/` -> repo root.
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent
_PI_AGENT_DIR = _REPO_ROOT / ".pi" / "agent"

# Path to the role-specific system prompt files. Phase 4.
# The orchestrator passes each file to its `pi` instance
# via `--append-system-prompt` (Task 4.3). The files live
# in the repo (not under `.pi/agent/`) so a developer can
# `cat` them, `vim` them from the Shell screen, or diff
# them in code review.
_PROMPTS_DIR = _REPO_ROOT / "backend" / "prompts"
_MIDDLEMAN_PROMPT = _PROMPTS_DIR / "middleman.md"
_WORKER_PROMPT = _PROMPTS_DIR / "worker.md"

_DEFAULT_APP_URL = "http://127.0.0.1:7778"

# The intent agent may inspect the app, but it must not mutate it.
# `--tools` is the primary capability boundary; PiRunner's event
# filter is a defense-in-depth backstop using the same allowlist.
MIDDLEMAN_READ_ONLY_TOOLS = ("read", "grep", "find", "ls")


def pi_cmd_for_role(
    role: str,
    *,
    provider: str | None = None,
    model: str | None = None,
    thinking: str | None = None,
    session_dir: Path | None = None,
    session_id: str | None = None,
) -> list[str]:
    """Return the argv used to spawn the `pi` CLI for a given role.

    Production default: real `pi` in RPC mode, using Pi's native
    settings for provider, model and thinking level. Tests monkey-patch this to return the
    in-tree `fake_pi*.py` fixture so the orchestrator can
    be exercised without an LLM or a `pi` install.

    The flags:

      * `--mode rpc`         : JSONL protocol over stdio
                               (the wire format the
                               orchestrator's read loop
                               speaks — Task 2.2+).
      * `--provider`, `--model`, `--thinking`: passed only for an
                               explicit selection; otherwise Pi reads
                               its shared settings.json defaults.
      * `--tools` (middle-man only): limits the intent agent to
                               `read`, `grep`, `find`, and `ls`.
                               PiRunner enforces the same allowlist
                               against emitted tool events.
      * `--no-session`       : the orchestrator drives
                               long-running pi processes
                               for the lifetime of the
                               FastAPI app. Persisting
                               per-process session files
                               is unnecessary and would
                               pollute the user's
                               `~/.pi/agent/sessions/`
                               with files tied to a
                               dev server. (Chat history
                               persistence is a separate
                               concern — the orchestrator
                               could log to
                               `logs/tasks.jsonl` in a
                               later task.)

    Phase 4 adds `--append-system-prompt <file>` to
    inject the role-specific prompt
    (`backend/prompts/middleman.md` for the middleman,
    `backend/prompts/worker.md` for the worker). The
    flag accepts a file path; pi reads the file's
    contents and appends them to the default system
    prompt (the built-in coding-assistant prompt). The
    file is the spec the agent works against — it
    defines the role, the constraints, the wire format
    the orchestrator expects (dispatch JSON for the
    middle-man, `<task:done summary="..."/>` for the
    worker).

    If the prompt file is missing (e.g. a fresh clone
    before the dev ran `git pull --rebase`), the spawn
    fails with a clear error from pi rather than a
    silent default. The orchestrator's lifespan
    tolerates a failed spawn (Task 3.1), so a missing
    prompt just means `/chat` 503s until the file is
    restored.

    Args:
        role: "middleman", "worker", or "control". Any other value
              raises ValueError so a typo in the caller
              fails fast.

    Returns:
        The argv list to pass to `PiRunner` / `os.execvp(e)`.

    Raises:
        ValueError: if `role` is not one of the known roles.
    """
    if role not in ("middleman", "worker", "control"):
        raise ValueError(f"unknown pi role: {role!r}")
    # An absent selection must defer entirely to Pi's shared settings.json.
    if role == "control":
        # The control process must be headless and must not load project
        # extensions, prompts, or tools.  During onboarding no model has been
        # chosen yet; --models provider/* lets Pi pick the first authenticated
        # model from its own bundled registry without inventing a model ID.
        argv = [
            "pi", "--mode", "rpc",
            "--no-session", "--no-tools", "--no-extensions", "--no-skills",
            "--no-prompt-templates", "--no-themes", "--no-context-files",
        ]
        if provider is not None:
            argv.extend(["--provider", provider])
        if model is not None:
            argv.extend(["--model", model])
        elif provider is not None:
            argv.extend(["--models", f"{provider}/*"])
        return argv
    prompt_file = (
        _MIDDLEMAN_PROMPT if role == "middleman" else _WORKER_PROMPT
    )
    argv = [
        "pi",
        "--mode", "rpc",
        "--session-dir", str(session_dir or (agent_dir() / 'role-sessions' / role)),
        "--session-id", session_id or str(uuid.uuid5(uuid.NAMESPACE_URL, f'seed:{role}')),
        "--append-system-prompt", str(prompt_file),
    ]
    for flag, value in (("--provider", provider), ("--model", model), ("--thinking", thinking)):
        if value is not None:
            argv.extend([flag, value])
    if role == "middleman":
        app_path = os.environ.get("SEED_APP_PATH", "/home/seed/app")
        argv.extend(
            [
                "--append-system-prompt",
                (
                    "Resolved Seed app workspace (trusted runtime config): "
                    f"{app_path}. Use this exact literal path with read-only "
                    "tools; do not try to expand $SEED_APP_PATH."
                ),
                "--tools",
                ",".join(MIDDLEMAN_READ_ONLY_TOOLS),
            ]
        )
    return argv


def pi_env_for_role(
    role: str,
    *,
    app_url: str | None = None,
    provider: str | None = None,
) -> dict[str, str]:
    """Return the env dict passed to the child `pi` process.

    Starts from the parent's environment but strips curated credential variables
    and the runtime capability. All roles use the same native Pi config directory.

    Args:
        role: "middleman", "worker", or "control".
        app_url: URL the worker must use to verify webapp routes.
                 The service supplies Flask's separate port-7778 URL in every
                 runtime. When omitted, an inherited `SEED_APP_URL` or that
                 default is used.

    Returns:
        A new env dict suitable for `os.execvpe`.

    Raises:
        ValueError: if `role` is not one of the known roles.
    """
    if role not in ("middleman", "worker", "control"):
        raise ValueError(f"unknown pi role: {role!r}")
    env = dict(os.environ)
    # The bearer capability belongs only to FastAPI's Android-facing boundary;
    # it must never be inherited by any Pi child process.
    env.pop(SEED_CAPABILITY_ENV, None)
    for name in PI_CREDENTIAL_ENV_VARS:
        env.pop(name, None)
    # All roles read the shared native Pi settings and auth files.
    config_dir = agent_dir()
    config_dir.mkdir(parents=True, exist_ok=True)
    env["PI_CODING_AGENT_DIR"] = str(config_dir)
    # Point the agent at the webapp. The middle-man and
    # worker prompts both reference `$SEED_APP_PATH` so
    # the same prompt file works in production
    # (`/home/seed/app/`) and dev (a path under the
    # developer's repo). The default is the production
    # path; the dev script (and tests) override via env.
    env["SEED_APP_PATH"] = os.environ.get(
        "SEED_APP_PATH", "/home/seed/app"
    )
    selected_app_url = app_url or os.environ.get("SEED_APP_URL")
    env["SEED_APP_URL"] = (selected_app_url or _DEFAULT_APP_URL).rstrip("/")
    return env


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
      * a `complete` broadcast on worker done.

    The pub-sub surface is in place from Task 3.1 so the WS
    route (3.2) can register its queue without further changes
    to the orchestrator.

    Attributes:
        middleman: The `PiRunner` driving the intent agent.
        worker:    The `PiRunner` driving the builder agent.
    """

    @staticmethod
    def role_session_id(role: str) -> str:
        if role not in ('middleman', 'worker'):
            raise ValueError('unknown persistent role')
        return str(uuid.uuid5(uuid.NAMESPACE_URL, f'seed:{role}'))

    # Cap on the per-subscriber queue. Slow clients drop events
    # rather than backpressure the reader tasks; the chat UI
    # would rather see a gap than freeze. 256 is a comfortable
    # headroom for the 3-5 events a typical turn emits.
    _SUBSCRIBER_QUEUE_MAXSIZE: int = 256

    def __init__(self, middleman: PiRunner, worker: PiRunner, task_store: TaskStore | None = None) -> None:
        self.middleman = middleman
        self.worker = worker
        self.task_store = task_store
        self.task_status: dict | None = task_store.load() if task_store else None
        if self.task_status and self.task_status['status'] in ('pending', 'running', 'cancel_pending'):
            self.task_status = {**self.task_status, 'status': 'interrupted', 'summary': 'Task interrupted by restart'}
            task_store.save(self.task_status)
        self._worker_report = ""
        self._worker_text = ""
        self._cancel_requested = False
        self._cancel_timeout_task: asyncio.Task | None = None
        self._abort_task: asyncio.Task | None = None
        self._last_progress_at = 0.0
        self._last_progress = ""
        self._last_agent_error = False
        self._last_agent_text = ""
        self._agent_ended = False
        self._worker_retry_pending = False
        self._worker_blocked = False
        # Each chat WS client subscribes by calling subscribe();
        # the orchestrator hands them a private queue and tracks
        # it in this set for broadcast. The set itself is mutated
        # only from the asyncio thread (no extra lock needed).
        self._subscribers: set[asyncio.Queue[dict]] = set()
        # Background tasks that read the middle-man and worker
        # PiRunners and broadcast each line to every subscriber.
        # Task 3.3 wires the middle-man loop; Task 3.5 wires the
        # worker loop. Both are created in start() and cancelled
        # in stop().
        self._read_middleman_task: asyncio.Task | None = None
        self._read_worker_task: asyncio.Task | None = None

    async def start(self) -> None:
        """Spawn both `pi` processes. No-op if already started.

        Each `PiRunner.start()` launches a pipe-backed `Popen` child, so
        this call returns once both executables have started. Exec failures
        are raised synchronously and the service lifespan records them while
        leaving the non-agent routes available.

        Also starts the background read loops (Task 3.3 +
        3.5). The loops shovel middle-man and worker output
        into the subscriber queues. Idempotent: a second
        call to start() is a no-op (the PiRunners are
        themselves idempotent, and the read tasks are only
        created if `self._read_*_task` is None).
        """
        await self.middleman.start()
        await self.worker.start()
        if self._read_middleman_task is None:
            self._read_middleman_task = asyncio.create_task(
                self._read_middleman_loop(),
                name="orchestrator-read-middleman",
            )
        if self._read_worker_task is None:
            # Task 3.5 will replace this no-op with the real
            # worker read loop. For 3.3 we only need the
            # middle-man stream.
            self._read_worker_task = asyncio.create_task(
                self._read_worker_loop(),
                name="orchestrator-read-worker",
            )

    async def stop(self) -> None:
        """Stop both runners. Idempotent; safe to call on a
        half-started orchestrator (the runners' own start() is
        idempotent and stop() is too).

        Also cancels the read loops before tearing down the
        runners. The read loops terminate on the next
        `read_lines()` iteration (their `async for` exits
        when the runner closes), but we cancel them eagerly
        so the queues drain to subscribers before the WS
        handlers see the connection close.
        """
        for task in (self._read_middleman_task, self._read_worker_task):
            if task is not None and not task.done():
                task.cancel()
                try:
                    await task
                except (asyncio.CancelledError, Exception):
                    # Read loops can raise on cancel if a
                    # broadcast is in flight; either way we
                    # want stop() to keep going.
                    pass
        self._read_middleman_task = None
        self._read_worker_task = None
        if self._cancel_timeout_task is not None:
            self._cancel_timeout_task.cancel()
            self._cancel_timeout_task = None
        if self._abort_task is not None:
            self._abort_task.cancel()
            self._abort_task = None
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
        if self.task_status is not None:
            q.put_nowait(dict(self.task_status))
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
        `complete` event. The list() copy avoids "set changed during
        iteration" if a subscribe/unsubscribe races the broadcast.
        """
        for q in list(self._subscribers):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                # Drop for slow consumer. The chat client will
                # see a gap; the reader keeps moving.
                pass

    async def send_to_middleman(self, message: str) -> None:
        """Forward a user message to the middle-man pi.

        Wraps the user text in a pi RPC `prompt` command
        and writes it to the middle-man's stdin. pi in
        `--mode rpc` expects one JSON command per line;
        the orchestrator has always done the wrapping
        (the test fixtures read the message from
        `cmd["message"]`, and the real `pi` reads it from
        the same field — see the RPC protocol docstring
        in `seed_backend/events.py`).

        The WS route (Task 3.2) calls this when a user
        hits "send" in the chat UI. The middle-man then
        thinks, asks a clarifying question, or emits a
        dispatch JSON block that the orchestrator
        forwards to the worker (Task 3.4).
        """
        await self._rpc(self.middleman, {"type": "prompt", "message": message, "streamingBehavior": "followUp"})

    @staticmethod
    async def _rpc(runner: PiRunner, command: dict) -> dict:
        response = await runner.rpc_request(command)
        if response.get("success") is not True:
            raise RuntimeError("pi command rejected")
        return response

    @property
    def _active(self) -> bool:
        return self.task_status is not None and self.task_status["status"] in ("pending", "running", "cancel_pending")

    async def _status(self, status: str, summary: str | None = None) -> None:
        if self.task_status is None:
            return
        self.task_status = {"type": "task_status", "taskId": self.task_status["taskId"], "status": status}
        if summary:
            self.task_status["summary"] = summary[:2048]
        if self.task_store:
            self.task_store.save(self.task_status)
        await self._broadcast(dict(self.task_status))

    async def stop_task(self) -> None:
        if not self._active or self._cancel_requested:
            return
        self._cancel_requested = True
        await self._status('cancel_pending')
        task_id = self.task_status['taskId']
        async def abort() -> None:
            try:
                await asyncio.wait_for(self._rpc(self.worker, {"type": "abort"}), timeout=2)
            except (Exception, asyncio.CancelledError):
                log.warning("worker abort command could not be delivered")
        self._abort_task = asyncio.create_task(abort())
        self._cancel_timeout_task = asyncio.create_task(self._cancel_fallback(task_id))

    async def _cancel_fallback(self, task_id: str, delay: float = 5) -> None:
        try:
            await asyncio.sleep(delay)
            if not (self._active and self.task_status['taskId'] == task_id and self._cancel_requested):
                return
            self._worker_blocked = True
            old = self.worker
            if self._abort_task is not None:
                self._abort_task.cancel()
            if self._read_worker_task is not None:
                self._read_worker_task.cancel()
                try:
                    await self._read_worker_task
                except asyncio.CancelledError:
                    pass
                self._read_worker_task = None
            await old.stop()  # Reaps the process before the slot is released.
            await self._status('interrupted', 'Worker did not confirm stopping')
            if isinstance(old, PiRunner):
                self.worker = PiRunner(old.cmd, old.role, strip_ansi=old.strip_ansi,
                    read_only_tools=old.read_only_tools, system_prompt=old.system_prompt,
                    auto_restart=old.auto_restart, max_restarts=old.max_restarts, env=old.env)
            await self.worker.start()
            self._read_worker_task = asyncio.create_task(self._read_worker_loop())
            self._cancel_requested = False
            self._worker_blocked = False
        except asyncio.CancelledError:
            pass
        except Exception:
            log.exception('worker restart after cancellation failed; dispatch remains blocked')

    async def _report_to_middleman(self, text: str) -> None:
        try:
            await self._rpc(self.middleman, {"type": "prompt", "message": text, "streamingBehavior": "followUp"})
        except Exception as exc:
            log.warning("middleman report failed: %r", exc)
            await self._broadcast({"type": "error", "message": "Middleman unavailable for task report"})

    async def _read_middleman_loop(self) -> None:
        """Read lines from the middle-man and broadcast chat events.

        Task 3.3 streams each line to subscribers as a
        `{"type": "middleman_line", "line": <text>}` event.

        Task 3.4 scans the accumulated text for a fenced
        ```json dispatch block; when one is found, the
        parsed dict is forwarded to the worker pi. The
        block is *also* broadcast as `middleman_line`
        events so the chat UI can render it as a card.

        Phase 4 added `translate_pi_line`: pi in
        `--mode rpc` emits JSONL events, not plain text
        lines. The translator unwraps the events so
        `middleman_line` carries the actual text delta
        (not the JSON wrapper). The fake pi fixtures
        emit plain text (no JSON); the translator
        passes those through unchanged. One helper,
        two wire formats.

        The buffer is bounded: if it grows past
        `_MIDDLEMAN_SCAN_BUFFER_MAX` bytes without a
        complete dispatch being found, the buffer is
        reset to avoid a runaway. A real middle-man
        either closes the block quickly or never emits
        one; the cap is just a safety net.

        The loop is a long-lived background task; it is
        cancelled by `stop()`. Malformed dispatch JSON is
        logged and discarded so later middle-man output can
        still be streamed and scanned. Unexpected reader or
        translation failures are logged before the loop exits.
        """
        buffer = ""
        streamed = ""
        middleman_failure: str | None = None
        try:
            async for line in self.middleman.read_lines():
                if line is None:
                    # EOF — child closed its output pipe. Done.
                    break
                try:
                    wire = json.loads(line)
                except ValueError:
                    wire = None
                if isinstance(wire, dict):
                    if wire.get("type") == "agent_end":
                        errors = [m.get("errorMessage", "") for m in wire.get("messages", [])
                                  if isinstance(m, dict) and m.get("role") == "assistant"
                                  and m.get("stopReason") == "error"]
                        if errors:
                            diagnostic = " ".join(str(error).lower() for error in errors)
                            middleman_failure = (
                                "Selected model rejected the request (context/output limit). Choose another model or reduce its max output."
                                if "context length" in diagnostic or "max_tokens" in diagnostic
                                else "Model request failed. Check the selected provider and model."
                            )
                        else:
                            middleman_failure = None
                        if not wire.get("willRetry") and middleman_failure:
                            await self._broadcast({"type": "error", "message": middleman_failure})
                            middleman_failure = None
                        if wire.get("willRetry"):
                            middleman_failure = None
                    elif wire.get("type") == "agent_settled":
                        if middleman_failure:
                            await self._broadcast({"type": "error", "message": middleman_failure})
                            middleman_failure = None
                    if wire.get("type") == "message_update" and wire.get("assistantMessageEvent", {}).get("type") == "text_delta":
                        streamed += wire["assistantMessageEvent"].get("delta", "")
                    elif wire.get("type") == "message_end":
                        content = wire.get("message", {}).get("content", [])
                        full = "".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text")
                        if streamed and full == streamed:
                            streamed = ""
                            continue
                        streamed = ""
                events, text_chunk = translate_pi_line(line, role="middleman")
                if events:
                    for ev in events:
                        await self._broadcast(ev)
                # Accumulate the text portion for dispatch
                # detection. `text_chunk` is empty for
                # lifecycle / tool events; the dispatcher
                # only cares about user-visible text.
                if text_chunk:
                    buffer += text_chunk
                if len(buffer) > _MIDDLEMAN_SCAN_BUFFER_MAX:
                    # Safety reset; the agent is producing a
                    # lot of text without a dispatch, give up
                    # and start fresh.
                    buffer = ""
                    continue
                try:
                    dispatch = extract_dispatch(buffer)
                except json.JSONDecodeError as exc:
                    log.warning(
                        "ignoring malformed middle-man dispatch: %s",
                        exc,
                    )
                    # Drop the completed malformed block. Keeping it would
                    # make every later scan fail on the same first match.
                    buffer = ""
                    await self._send_dispatch_to_worker({})
                    continue
                if dispatch is not None:
                    if dispatch.get("type") == "steer_worker":
                        if self._active and dispatch.get("taskId") == self.task_status["taskId"] and isinstance(dispatch.get("message"), str) and dispatch["message"].strip():
                            try:
                                await self._rpc(self.worker, {"type": "steer", "message": dispatch["message"]})
                            except Exception:
                                log.warning("worker steer rejected", exc_info=True)
                    else:
                        await self._send_dispatch_to_worker(dispatch)
                    # Clear the buffer past the match so a
                    # second dispatch in the same turn is
                    # detected (and so a half-formed block
                    # doesn't trip us up later).
                    buffer = ""
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.exception("middleman read loop crashed: %r", exc)

    async def _send_dispatch_to_worker(self, dispatch: dict) -> None:
        if self._active or self._worker_blocked:
            await self._broadcast({"type": "error", "message": "Worker unavailable for a new task"})
            return
        if not isinstance(dispatch, dict) or dispatch.get("intent") not in ("build_feature", "fix_bug", "refactor") or not all(isinstance(dispatch.get(key), str) and dispatch[key].strip() for key in ("feature", "spec")):
            self.task_status = {"type": "task_status", "taskId": uuid.uuid4().hex, "status": "pending"}
            await self._status("failed", "Invalid dispatch")
            return
        self.task_status = {"type": "task_status", "taskId": uuid.uuid4().hex, "status": "pending"}
        self._worker_report = ""
        self._worker_text = ""
        self._cancel_requested = False
        self._last_agent_error = False
        self._last_agent_text = ""
        self._agent_ended = False
        self._worker_retry_pending = False
        self._last_progress_at = 0.0
        self._last_progress = ""
        if self.task_store:
            self.task_store.save(self.task_status)
        await self._broadcast(dict(self.task_status))
        try:
            await self._rpc(self.worker, {"type": "prompt", "message": json.dumps({**dispatch, "taskId": self.task_status["taskId"]})})
            await self._status("running")
        except Exception as exc:
            log.warning("dispatch forward to worker failed: %r", exc)
            await self._status("failed", "Worker unavailable")
            await self._report_to_middleman("Worker task failed: Worker unavailable")

    async def _read_worker_loop(self) -> None:
        """Read lines from the worker and broadcast each one.

        Task 3.5 broadcasts each line as a
        `{"type": "worker_line", "line": <line>}` event.

        A `<task:done/>` marker produces one `complete` event with a
        summary. The marker itself is not broadcast as a worker line.

        The loop runs until the worker's `read_lines()`
        async generator terminates (the child exited). It
        is a long-lived background task cancelled by
        `stop()`. Exceptions other than `CancelledError`
        are logged and the loop continues on the next
        iteration.
        """
        try:
            async for line in self.worker.read_lines():
                if line is None:
                    # EOF — child closed its output pipe. Done.
                    break
                # Phase 4: worker output may be plain text
                # (fake fixture) or JSONL events (real pi).
                # The translator unwraps events; for the
                # worker we only care about the text and
                # the `<task:done/>` marker, which can
                # appear either as a literal line (fake
                # fixture) or in the accumulated text chunk
                # of a `text_delta` event (real pi).
                events, text_chunk = translate_pi_line(line, role="worker")
                if events:
                    for ev in events:
                        if ev.get("type") != "error":
                            await self._broadcast(ev)
                if not self._active:
                    continue
                try:
                    pi_event = json.loads(line)
                except ValueError:
                    pi_event = {}
                if not isinstance(pi_event, dict):
                    pi_event = {}
                if pi_event.get("type") == "response":
                    if pi_event.get("success") is False and not self._cancel_requested:
                        await self._status("failed", "Worker could not start the task")
                        await self._report_to_middleman("Worker could not start the task. Tell the user it failed without exposing provider diagnostics.")
                    continue
                if pi_event.get("type") == "agent_end":
                    self._worker_retry_pending = bool(pi_event.get("willRetry", False))
                    self._agent_ended = not self._worker_retry_pending
                    # agent_end is a turn boundary, not a task boundary: queued
                    # follow-ups or retries may still be processed before settlement.
                    messages = pi_event.get("messages")
                    if isinstance(messages, list):
                        assistant = [m for m in messages if isinstance(m, dict) and m.get("role") == "assistant"]
                        if assistant:
                            last = assistant[-1]
                            self._last_agent_error = any(m.get('stopReason') in ('error', 'aborted') or bool(m.get('errorMessage')) for m in assistant)
                            content = last.get("content")
                            if isinstance(content, list):
                                self._last_agent_text = "".join(b.get("text", "") for b in content if isinstance(b, dict) and b.get("type") == "text" and isinstance(b.get("text"), str))[-2048:]
                    if self._agent_ended:
                        await self._finalize_worker_turn()
                    continue
                if pi_event.get("type") == "agent_settled":
                    if not self._worker_retry_pending:
                        await self._finalize_worker_turn()
                    continue
                # Plain-text fake Pi fixtures use the same task-report marker.
                if not pi_event and text_chunk:
                    marker = parse_task_done(text_chunk)
                    if marker is not None:
                        self._worker_report = marker[:2048]
                # Only assistant text deltas count: message_end duplicates streamed
                # content and arbitrary tool/unknown RPC events are not progress.
                if pi_event.get("type") == "message_update" and pi_event.get("assistantMessageEvent", {}).get("type") == "text_delta":
                    self._worker_text = (self._worker_text + text_chunk)[-8192:]
                    summary = parse_task_done(self._worker_text)
                    if summary is not None:
                        self._worker_report = summary[:2048]
                    for match in TASK_PROGRESS_RE.finditer(self._worker_text):
                        if match.end() <= len(self._worker_text) - len(text_chunk):
                            continue
                        if match.group("task_id") != self.task_status["taskId"]:
                            continue
                        progress = match.group("text").strip()
                        now = time.monotonic()
                        if progress and progress != self._last_progress and (not self._last_progress_at or now - self._last_progress_at >= 2):
                            self._last_progress_at = now
                            self._last_progress = progress
                            await self._report_to_middleman(f"Worker progress for task {self.task_status['taskId']}: {progress}")
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.exception("worker read loop crashed: %r", exc)
        if self._active:
            await self._status("cancelled" if self._cancel_requested else "failed", None if self._cancel_requested else "Worker exited")
            if not self._cancel_requested:
                await self._report_to_middleman("Worker exited before completing the task")

    async def _finalize_worker_turn(self) -> None:
        if not self._active:
            return
        if self._cancel_timeout_task is not None:
            self._cancel_timeout_task.cancel()
            self._cancel_timeout_task = None
        if self._cancel_requested:
            await self._status("cancelled")
        elif self._last_agent_error or not self._agent_ended or not (self._worker_report or parse_task_done(self._last_agent_text) is not None):
            await self._status("failed", "Worker failed")
            await self._report_to_middleman(f"Worker task {self.task_status['taskId']} failed. Explain the failure to the user without provider diagnostics.")
        else:
            summary = self._worker_report or parse_task_done(self._last_agent_text) or "Task complete"
            await self._status("completed", summary)
            await self._report_to_middleman(f"Worker task {self.task_status['taskId']} completed: {summary[:2048]}. Summarize the result for the user.")
