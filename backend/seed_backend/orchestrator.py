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
