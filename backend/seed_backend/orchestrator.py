"""Orchestrator: the two-pi agent container (Task 3.1+).

The orchestrator owns the middle-man and worker `PiRunner`
subprocesses and exposes a tiny pub-sub interface that the
WebSocket `/chat` route consumes. It is a thin container; the
`PiRunner`s do the actual PTY + read-loop work, and the route
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
from pathlib import Path

from seed_backend.events import (
    parse_task_done,
    translate_pi_line,
    WS_TYPE_APP_RELOAD,
    WS_TYPE_COMPLETE,
    WS_TYPE_MIDDLEMAN_LINE,
    WS_TYPE_WORKER_LINE,
)
from seed_backend.middleman import extract_dispatch
from seed_backend.pi_runner import PiRunner

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

# Default provider / model for the v0.1 test stack. Both are
# overridable via env so a developer can swap to a different
# model without touching code (e.g. `SEED_PI_MODEL=claude-haiku-4-5`
# for a slightly smarter / more expensive run).
_DEFAULT_PI_PROVIDER = "opencode-go"
_DEFAULT_PI_MODEL = "deepseek-v4-flash"
_DEFAULT_PI_THINKING = "low"


def pi_cmd_for_role(role: str) -> list[str]:
    """Return the argv used to spawn the `pi` CLI for a given role.

    Production default: real `pi` in RPC mode, pointed at
    the cheap `deepseek-v4-flash` model on the `opencode-go`
    provider. Tests monkey-patch this to return the
    in-tree `fake_pi*.py` fixture so the orchestrator can
    be exercised without an LLM or a `pi` install.

    The flags:

      * `--mode rpc`         : JSONL protocol over stdio
                               (the wire format the
                               orchestrator's read loop
                               speaks — Task 2.2+).
      * `--provider`         : overrides the default in
                               `.pi/agent/settings.json` so
                               a misconfigured local file
                               can't silently route to a
                               different model. Also
                               makes the test explicit.
      * `--model`            : same rationale as
                               `--provider`. Cheap
                               `deepseek-v4-flash` by
                               default for testing.
      * `--thinking`         : "low" for speed/cost; the
                               model is a "flash" tier
                               so high thinking is
                               overkill for the
                               orchestrator's prompts.
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
        role: "middleman" or "worker". Any other value
              raises ValueError so a typo in the caller
              fails fast.

    Returns:
        The argv list to pass to `PiRunner` / `os.execvp(e)`.

    Raises:
        ValueError: if `role` is not one of the known roles.
    """
    if role not in ("middleman", "worker"):
        raise ValueError(f"unknown pi role: {role!r}")
    provider = os.environ.get("SEED_PI_PROVIDER", _DEFAULT_PI_PROVIDER)
    model = os.environ.get("SEED_PI_MODEL", _DEFAULT_PI_MODEL)
    thinking = os.environ.get("SEED_PI_THINKING", _DEFAULT_PI_THINKING)
    prompt_file = (
        _MIDDLEMAN_PROMPT if role == "middleman" else _WORKER_PROMPT
    )
    return [
        "pi",
        "--mode", "rpc",
        "--provider", provider,
        "--model", model,
        "--thinking", thinking,
        "--no-session",
        "--append-system-prompt", str(prompt_file),
    ]


def pi_env_for_role(role: str) -> dict[str, str]:
    """Return the env dict passed to the child `pi` process.

    Starts from the parent's `os.environ` (so API keys set
    in the shell, e.g. `OPENCODE_API_KEY`, are inherited
    — we never want to bake secrets into argv) and
    overrides `PI_CODING_AGENT_DIR` to point at the
    project's local config directory. This is what makes
    the project's `.pi/agent/settings.json` (with
    `defaultProvider=opencode-go`, `defaultModel=
    deepseek-v4-flash`) take effect, independent of the
    user's `~/.pi/agent/`.

    The path is computed once at import time (see
    `_PI_AGENT_DIR`). If the directory doesn't exist yet,
    we create it on first call so a fresh clone works
    without a manual `mkdir`. We do NOT touch the
    `settings.json` if it already exists — the user may
    have customised it.

    Args:
        role: "middleman" or "worker". Currently unused
              (both roles share the same env), but kept
              in the signature for symmetry with
              `pi_cmd_for_role` and to leave room for
              per-role env differences in the future
              (e.g. separate session dirs).

    Returns:
        A new env dict suitable for `os.execvpe`.

    Raises:
        ValueError: if `role` is not one of the known roles.
    """
    if role not in ("middleman", "worker"):
        raise ValueError(f"unknown pi role: {role!r}")
    env = dict(os.environ)
    # Ensure the local config dir exists. The settings.json
    # inside it is committed; the dir itself is what pi
    # reads from. A fresh clone has the file but not the
    # dir, so create on first use.
    _PI_AGENT_DIR.mkdir(parents=True, exist_ok=True)
    env["PI_CODING_AGENT_DIR"] = str(_PI_AGENT_DIR)
    # Point the agent at the webapp. The middle-man and
    # worker prompts both reference `$SEED_APP_PATH` so
    # the same prompt file works in production
    # (`/home/seed/app/`) and dev (a path under the
    # developer's repo). The default is the production
    # path; the dev script (and tests) override via env.
    env["SEED_APP_PATH"] = os.environ.get(
        "SEED_APP_PATH", "/home/seed/app"
    )
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
        # Background tasks that read the middle-man and worker
        # PiRunners and broadcast each line to every subscriber.
        # Task 3.3 wires the middle-man loop; Task 3.5 wires the
        # worker loop. Both are created in start() and cancelled
        # in stop().
        self._read_middleman_task: asyncio.Task | None = None
        self._read_worker_task: asyncio.Task | None = None

    async def start(self) -> None:
        """Spawn both `pi` processes. No-op if already started.

        Each `PiRunner.start()` does its own fork+exec, so this
        call returns as soon as both children are running (or
        have already failed to exec — the runner keeps the pid
        in that case so `stop()` can still reap the child).

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
        cmd = (
            json.dumps({"type": "prompt", "message": message}) + "\n"
        )
        await self.middleman.send(cmd)

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
        cancelled by `stop()`. Exceptions (other than
        `CancelledError`) are logged and the loop
        continues on the next iteration, so a transient
        error in one iteration doesn't take down the
        whole stream.
        """
        buffer = ""
        try:
            async for line in self.middleman.read_lines():
                if line is None:
                    # EOF — child closed the slave end. Done.
                    break
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
                dispatch = extract_dispatch(buffer)
                if dispatch is not None:
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
        """Forward a parsed dispatch dict to the worker pi.

        Wraps the JSON in a pi RPC `prompt` command (the
        shape pi expects on stdin in `--mode rpc`) and
        writes it to the worker's stdin. The worker's
        `worker.md` prompt tells the agent to read the
        dispatch spec and execute it.

        Worker send failures (worker not running, broken
        pipe, etc.) are logged and swallowed — the chat
        stream must not die because the worker is
        unhealthy. The middle-man has already emitted
        its dispatch; if the worker is down, the user
        sees the dispatch as a card and an error log.
        A retry / reconnect is a future task.
        """
        try:
            cmd = (
                json.dumps(
                    {"type": "prompt", "message": json.dumps(dispatch)}
                )
                + "\n"
            )
            await self.worker.send(cmd)
        except Exception as exc:
            log.warning(
                "dispatch forward to worker failed: %r", exc
            )

    async def _read_worker_loop(self) -> None:
        """Read lines from the worker and broadcast each one.

        Task 3.5 broadcasts each line as a
        `{"type": "worker_line", "line": <line>}` event.

        Task 3.6 also watches for a `<task:done/>` marker:
        when the worker emits that exact string on a line,
        the orchestrator broadcasts two extra events to all
        subscribers — `complete` (with a summary) and
        `app_reload` (a pure reload signal for the App
        screen WebView). The marker line itself is NOT
        broadcast as a worker_line (it is a control
        signal, not user-facing content).

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
                    # EOF — child closed the slave end. Done.
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
                        await self._broadcast(ev)
                # Check both the raw line and the text
                # chunk for the marker. The fake fixture
                # writes the marker as its own line; real
                # pi streams it as the end of a text
                # delta.
                for candidate in (line, text_chunk):
                    summary = parse_task_done(candidate)
                    if summary is None:
                        continue
                    # Fan out the done signal. The chat UI
                    # uses `complete` to render a summary
                    # bubble; the App screen WebView uses
                    # `app_reload` to refresh at the new
                    # state. We do NOT also broadcast the
                    # marker line as a worker_line — it is
                    # a control marker, not user-facing
                    # text.
                    await self._broadcast(
                        {
                            "type": WS_TYPE_COMPLETE,
                            "summary": summary or _DEFAULT_COMPLETE_SUMMARY,
                        }
                    )
                    await self._broadcast(
                        {"type": WS_TYPE_APP_RELOAD}
                    )
                    break  # one marker is enough
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            log.exception("worker read loop crashed: %r", exc)
