"""Shared helpers for the WebSocket /chat tests.

Used by `test_chat_ws.py`, `test_middleman_stream.py`,
`test_dispatch.py`, `test_worker_stream.py`, and
`test_complete_signal.py`. The module is private to the
tests package (the `_` prefix is the conventional Python
signal for "not part of the public API").
"""
from __future__ import annotations

import json
import queue
import sys
import threading
import time
from pathlib import Path
from typing import Callable, Optional

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app


FIXTURES_DIR = Path(__file__).parent / "fixtures"


def _fake_pi_cmd(name: str) -> list[str]:
    return [sys.executable, str(FIXTURES_DIR / name)]


def _fake_pi_dispatch_cmd() -> list[str]:
    return _fake_pi_cmd("fake_pi_dispatch.py")


def _fake_pi_log_cmd(log_path: Path) -> list[str]:
    return [
        sys.executable,
        str(FIXTURES_DIR / "fake_pi_log.py"),
        "--log",
        str(log_path),
    ]


def _fake_pi_worker_cmd() -> list[str]:
    return _fake_pi_cmd("fake_pi_worker_response.py")


def make_log_cmd_factory(mm_log: Path, w_log: Path) -> Callable[[str], list[str]]:
    """Build a `pi_cmd_for_role` that points each role at a distinct
    `fake_pi_log.py` log file. Used by `test_chat_ws.py`."""
    def cmd_for_role(role: str) -> list[str]:
        if role == "middleman":
            return _fake_pi_log_cmd(mm_log)
        if role == "worker":
            return _fake_pi_log_cmd(w_log)
        raise ValueError(f"unknown role: {role!r}")
    return cmd_for_role


def make_dispatch_worker_cmd_factory() -> Callable[[str], list[str]]:
    """Build a `pi_cmd_for_role` that points middle-man at the
    dispatch emitter and worker at the worker-response fixture."""
    def cmd_for_role(role: str) -> list[str]:
        if role == "middleman":
            return _fake_pi_dispatch_cmd()
        if role == "worker":
            return _fake_pi_worker_cmd()
        raise ValueError(f"unknown role: {role!r}")
    return cmd_for_role


def wait_for_file(path: Path, timeout_s: float = 5.0) -> bool:
    """Poll `path` until it exists or `timeout_s` elapses."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        if path.exists():
            return True
        time.sleep(0.05)
    return False


class Receiver:
    """Wrap a starlette WebSocketTestSession with a per-receive timeout.

    starlette 1.3.1's `WebSocketTestSession.receive_text()` blocks
    indefinitely if the server keeps the connection open but never
    sends a frame. That is exactly what happens when we wait for a
    stream the server does not (yet) produce — a hang that burns
    the whole test timeout and gives no diagnostic.

    This helper runs `receive_text` in a daemon thread and exposes
    `next_frame(timeout)` which returns one parsed JSON frame or
    raises `TimeoutError` if the server is silent for `timeout`
    seconds. A sentinel is queued when the server closes the WS
    (the underlying receive raises `WebSocketDisconnect`), surfaced
    as `EOFError` so the caller can tell "closed early" apart from
    "just slow".
    """

    _EOF = object()

    def __init__(self, ws) -> None:
        self._ws = ws
        self._q: queue.Queue = queue.Queue()
        self._thread = threading.Thread(target=self._run, daemon=True)
        self._thread.start()

    def _run(self) -> None:
        try:
            while True:
                self._q.put(self._ws.receive_text())
        except Exception:
            self._q.put(self._EOF)

    def next_frame(self, timeout: float) -> dict:
        """Block up to `timeout` seconds for the next frame.

        Raises:
            TimeoutError: server silent for `timeout` seconds.
            EOFError:     server closed the WS.
        """
        try:
            item = self._q.get(timeout=timeout)
        except queue.Empty:
            # `queue.Empty` is the standard-library sentinel
            # for "no item within timeout"; surface it as
            # `TimeoutError` so callers only need to handle
            # one "no frame" exception type.
            raise TimeoutError(
                f"no frame within {timeout}s"
            ) from None
        if item is self._EOF:
            raise EOFError("chat WebSocket closed before frame arrived")
        return json.loads(item)


def drain(
    rcv: Receiver,
    predicate: Callable[[dict], bool],
    n: int,
    timeout_s: float = 5.0,
) -> list[dict]:
    """Receive frames until `predicate(frame)` is true `n` times.

    Used to filter for a specific event type on a WS that carries
    multiple event kinds (e.g. middleman_line + worker_line +
    complete + app_reload on the same chat connection).
    """
    deadline = time.monotonic() + timeout_s
    out: list[dict] = []
    while len(out) < n and time.monotonic() < deadline:
        try:
            frame = rcv.next_frame(timeout=0.5)
        except TimeoutError:
            continue
        except EOFError:
            break
        if predicate(frame):
            out.append(frame)
    return out


def collect_all(
    rcv: Receiver, timeout_s: float = 5.0
) -> list[dict]:
    """Return every frame received within `timeout_s` seconds.

    Unlike `drain` (which stops after N predicate matches),
    this helper returns the full stream so the caller can
    filter for multiple event types out of one window — the
    common case in Phase 3 tests where a single user_message
    produces a `complete` + `app_reload` pair that should
    both be asserted on.

    Stops early on EOF (server closed the WS) and returns
    whatever was collected so far.
    """
    deadline = time.monotonic() + timeout_s
    out: list[dict] = []
    while time.monotonic() < deadline:
        try:
            frame = rcv.next_frame(timeout=0.5)
        except TimeoutError:
            continue
        except EOFError:
            break
        out.append(frame)
    return out


def filter_by_type(frames: list[dict], type_value: str) -> list[dict]:
    """Return the subset of `frames` whose `type` equals `type_value`."""
    return [f for f in frames if f.get("type") == type_value]
