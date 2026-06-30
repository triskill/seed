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

from seed_backend.pi_runner import PiRunner


def pi_cmd_for_role(role: str) -> list[str]:
    """Return the argv used to spawn the `pi` CLI for a given role.

    Production default: real `pi` in RPC mode (TBD flags — Phase 4
    will pin the exact invocation). Tests monkey-patch this to
    return the in-tree `fake_pi*.py` fixture so the orchestrator
    can be exercised without an LLM or a `pi` install.

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
        """Forward a user message to the middle-man.

        The WS route (Task 3.2) calls this when a user hits
        "send" in the chat UI. The middle-man then thinks, asks
        a clarifying question, or emits a dispatch JSON block
        that the orchestrator forwards to the worker (Task 3.4).
        """
        await self.middleman.send(message)

    async def _read_middleman_loop(self) -> None:
        """Read lines from the middle-man and broadcast each one.

        Task 3.3. Loops until the middle-man's `read_lines()`
        async generator terminates (i.e. the child exited).
        Each line is broadcast as a `{"type": "middleman_line",
        "line": <line>}` event so chat clients can render the
        agent's stream of thought in the chat UI.

        The loop is a long-lived background task; it is
        cancelled by `stop()`. Exceptions (other than
        `CancelledError`) are logged and the loop continues
        on the next iteration, so a transient error in one
        iteration doesn't take down the whole stream.
        """
        try:
            async for line in self.middleman.read_lines():
                if line is None:
                    # EOF — child closed the slave end. Done.
                    break
                await self._broadcast(
                    {"type": "middleman_line", "line": line}
                )
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            import logging
            logging.getLogger(__name__).exception(
                "middleman read loop crashed: %r", exc
            )

    async def _read_worker_loop(self) -> None:
        """Read lines from the worker and broadcast each one.

        Task 3.5 wires the real implementation. Until then
        this is a no-op loop that exists only so
        `Orchestrator.start()` can create the task uniformly
        and the cancel/await in `stop()` doesn't have to
        special-case "not started yet".
        """
        # Task 3.5: broadcast each line as
        #   {"type": "worker_line", "line": <line>}
        # For 3.3, the worker never receives input (no
        # dispatch is wired yet), so the worker sits on
        # readline and this loop blocks on `read_lines()`.
        # When the orchestrator is stopped, the runner's
        # `stop()` closes the master fd, EOF arrives, and
        # this loop exits.
        try:
            async for line in self.worker.read_lines():
                if line is None:
                    break
                # No-op until Task 3.5 wires the broadcast.
        except asyncio.CancelledError:
            raise
        except Exception as exc:
            import logging
            logging.getLogger(__name__).exception(
                "worker read loop crashed: %r", exc
            )
