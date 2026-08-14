"""Pipe-backed wrapper around the ``pi`` RPC CLI.

``pi --mode rpc`` is a JSONL protocol over stdin/stdout and does not need a
terminal. Earlier Seed versions nevertheless launched it with ``pty.openpty``
and ``os.fork``. Android PRoot returns ``ENOSYS`` for ``fork(2)``, which made
that launcher the blocker for the embedded agent loop.

The runner now uses ``subprocess.Popen`` with ordinary pipes, merged stderr,
and ``start_new_session=True``. This is the same Android-compatible process
model used by the embedded Shell endpoint. It preserves streaming, bounded
backpressure, process-group termination, ANSI cleanup, tool filtering, prompt
preload, and bounded auto-restart without calling Python's ``os.fork``.
"""
from __future__ import annotations

import asyncio
import codecs
import concurrent.futures
import json
import logging
import os
import re
import signal
import subprocess
import threading
from typing import AsyncIterator, BinaryIO


log = logging.getLogger(__name__)

# Common ANSI sequences produced by tools invoked by pi.
_ANSI_CSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
_ANSI_OSC_RE = re.compile(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")


def _strip_ansi(text: str) -> str:
    """Return ``text`` with common CSI and OSC sequences removed."""
    text = _ANSI_CSI_RE.sub("", text)
    return _ANSI_OSC_RE.sub("", text)


def _check_tool_call(line: str, allowed: set[str]) -> dict | None:
    """Return violation details for a disallowed pi tool-start event."""
    line = line.strip()
    if not line.startswith("{"):
        return None
    try:
        event = json.loads(line)
    except ValueError:
        return None
    if not isinstance(event, dict):
        return None
    if event.get("type") != "tool_execution_start":
        return None
    tool_name = event.get("toolName")
    if not isinstance(tool_name, str) or tool_name in allowed:
        return None
    return {"tool_name": tool_name, "event": event}


class PiRunnerNotRunning(RuntimeError):
    """Raised when the runner has no live child process."""


class ToolCallBlocked(RuntimeError):
    """Raised when a read-only pi process requests a disallowed tool."""

    def __init__(self, tool_name: str, event: dict) -> None:
        self.tool_name = tool_name
        self.event = event
        super().__init__(f"pi tried to call disallowed tool {tool_name!r}")


class PiRunner:
    """Run a ``pi``-like RPC subprocess and stream its output by line.

    The child receives a dedicated session/process group so ``stop()`` and the
    tool filter terminate helpers as well as the leader. Blocking pipe reads,
    writes, and waits use a small runner-owned executor; the asyncio event loop
    remains responsive and the executor is shut down deterministically.
    """

    def __init__(
        self,
        cmd: list[str],
        role: str,
        strip_ansi: bool = True,
        read_only_tools: set[str] | None = None,
        system_prompt: str = "",
        auto_restart: bool = False,
        max_restarts: int = 5,
        env: dict[str, str] | None = None,
    ) -> None:
        self.cmd = list(cmd)
        self.role = role
        self.env = dict(env) if env is not None else None
        self.strip_ansi = strip_ansi
        self.read_only_tools = (
            set(read_only_tools) if read_only_tools is not None else None
        )
        self.system_prompt = system_prompt
        self.auto_restart = auto_restart
        self.max_restarts = max_restarts
        self._restart_count = 0

        # ``pid`` remains public for lifecycle/status callers and tests.
        self.pid: int | None = None
        self._process: subprocess.Popen[bytes] | None = None
        self._reader_task: asyncio.Task[None] | None = None
        self._preload_task: asyncio.Task[None] | None = None
        self._ready_event = asyncio.Event()
        self._ready_process: subprocess.Popen[bytes] | None = None
        self._ready_error: BaseException | None = None
        self._stopping = False

        self._lines: asyncio.Queue[str | None] = asyncio.Queue(maxsize=1024)
        self._violations: asyncio.Queue[dict] = asyncio.Queue(maxsize=1)
        self._write_lock = threading.Lock()
        self._executor = concurrent.futures.ThreadPoolExecutor(
            max_workers=3,
            thread_name_prefix=f"pi-runner-{role}",
        )
        self._executor_shutdown = False

    async def start(self) -> None:
        """Start the child with pipes. Calling twice while active is a no-op."""
        if self.pid is not None:
            return
        if self._executor_shutdown:
            raise RuntimeError("PiRunner cannot be restarted after stop()")
        self._stopping = False
        await self._spawn()
        self._reader_task = asyncio.create_task(
            self._read_loop(),
            name=f"pi-runner-read-{self.role}",
        )
        process = self._process
        assert process is not None
        self._preload_task = self._begin_preload(process)
        try:
            await self._preload_task
        except BaseException:
            await self.stop()
            raise

    async def _spawn(self) -> None:
        """Launch one child without a Python-level ``os.fork`` call."""
        def _popen() -> subprocess.Popen[bytes]:
            return subprocess.Popen(
                self.cmd,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                env=self.env,
                bufsize=0,
                start_new_session=True,
                close_fds=True,
            )

        process = await self._executor_submit(_popen)
        if process.stdin is None or process.stdout is None:
            process.kill()
            process.wait()
            raise RuntimeError("pi subprocess pipes were not created")
        self._process = process
        self.pid = process.pid
        self._ready_event = asyncio.Event()
        self._ready_process = None
        self._ready_error = None

    def _begin_preload(
        self,
        process: subprocess.Popen[bytes],
    ) -> asyncio.Task[None]:
        """Start preload concurrently with stdout draining for this generation."""
        ready_event = self._ready_event
        return asyncio.create_task(
            self._preload_generation(process, ready_event),
            name=f"pi-runner-preload-{self.role}",
        )

    async def _preload_generation(
        self,
        process: subprocess.Popen[bytes],
        ready_event: asyncio.Event,
    ) -> None:
        """Preload one generation and publish it as ready for sends."""
        try:
            if self.system_prompt:
                if process.stdin is None:
                    raise BrokenPipeError("pi subprocess stdin is unavailable")
                payload = (self.system_prompt + "\n\n").encode("utf-8")
                await self._write(process.stdin, payload)
            if self._process is process and not self._stopping:
                self._ready_process = process
                ready_event.set()
        except (BrokenPipeError, OSError) as exc:
            error = RuntimeError(
                f"failed to preload system prompt: child died ({exc})"
            )
            if self._process is process:
                self._ready_error = error
                ready_event.set()
            self._signal_process_group(process, signal.SIGTERM)
            raise error from exc

    async def _restart(self) -> bool:
        """Start another generation if the configured restart budget remains."""
        if self._restart_count >= self.max_restarts or self._stopping:
            return False
        self._restart_count += 1
        await self._spawn()
        process = self._process
        assert process is not None
        task = self._begin_preload(process)
        self._preload_task = task

        # The read loop must immediately drain the new stdout pipe, so it cannot
        # await a potentially large preload here. Consume/log failures through
        # a callback; the failed generation is terminated and EOF drives the
        # normal restart path.
        def _consume_result(done: asyncio.Task[None]) -> None:
            try:
                done.result()
            except (asyncio.CancelledError, Exception) as exc:
                if not isinstance(exc, asyncio.CancelledError):
                    log.warning("pi preload after restart failed: %r", exc)

        task.add_done_callback(_consume_result)
        return True

    async def send(self, message: str) -> None:
        """Send one newline-terminated RPC command to the child."""
        while True:
            process = self._process
            ready_event = self._ready_event
            if self.pid is None or process is None or process.stdin is None:
                raise PiRunnerNotRunning("PiRunner.send() called before start()")
            await ready_event.wait()
            if process is not self._process:
                continue
            if self._ready_error is not None:
                raise PiRunnerNotRunning(
                    "pi subprocess failed during startup"
                ) from self._ready_error
            if self._ready_process is not process or process.poll() is not None:
                raise PiRunnerNotRunning("pi subprocess is not ready")
            break

        payload = message if message.endswith("\n") else message + "\n"
        try:
            await self._write(process.stdin, payload.encode("utf-8"))
        except (BrokenPipeError, OSError) as exc:
            raise PiRunnerNotRunning("pi subprocess stdin is closed") from exc

    async def _write(self, stream: BinaryIO, payload: bytes) -> None:
        """Write all bytes without interleaving concurrent chat sends."""
        def _write_all() -> None:
            with self._write_lock:
                view = memoryview(payload)
                while view:
                    written = os.write(stream.fileno(), view)
                    if written <= 0:
                        raise BrokenPipeError("short write to pi subprocess")
                    view = view[written:]

        await self._executor_submit(_write_all)

    async def _executor_submit(self, fn, *args):
        if self._executor_shutdown:
            raise RuntimeError("PiRunner executor is shut down")
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._executor, fn, *args)

    async def read_lines(self) -> AsyncIterator[str]:
        """Yield decoded output until EOF, or raise on a tool violation."""
        if self.pid is None:
            raise PiRunnerNotRunning(
                "PiRunner.read_lines() called before start()"
            )
        while True:
            get_line = asyncio.create_task(self._lines.get())
            get_violation = asyncio.create_task(self._violations.get())
            try:
                done, pending = await asyncio.wait(
                    {get_line, get_violation},
                    return_when=asyncio.FIRST_COMPLETED,
                )
            except BaseException:
                get_line.cancel()
                get_violation.cancel()
                raise
            for task in pending:
                task.cancel()
            if get_violation in done:
                violation = get_violation.result()
                raise ToolCallBlocked(
                    violation["tool_name"],
                    violation["event"],
                )
            line = get_line.result()
            if line is None:
                return
            yield line

    async def _read_loop(self) -> None:
        """Drain stdout, preserving UTF-8 characters split across reads."""
        buffer = ""
        decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")
        decoder_process: subprocess.Popen[bytes] | None = None

        while not self._stopping:
            process = self._process
            if process is None or process.stdout is None:
                return
            if process is not decoder_process:
                buffer = ""
                decoder = codecs.getincrementaldecoder("utf-8")(
                    errors="replace"
                )
                decoder_process = process
            try:
                chunk = await self._executor_submit(
                    os.read,
                    process.stdout.fileno(),
                    4096,
                )
            except (OSError, ValueError):
                if self._stopping:
                    return
                chunk = b""

            if not chunk:
                buffer += decoder.decode(b"", final=True)
                if buffer:
                    if not await self._handle_line(buffer):
                        return
                    buffer = ""

                if self.auto_restart and self._process is process:
                    await self._finish_generation(process)
                    try:
                        if await self._restart():
                            continue
                    except Exception as exc:
                        log.exception("pi auto-restart failed: %r", exc)
                await self._enqueue_line(None)
                return

            buffer += decoder.decode(chunk, final=False)
            while "\n" in buffer:
                line, _, buffer = buffer.partition("\n")
                if line.endswith("\r"):
                    line = line[:-1]
                if not await self._handle_line(line):
                    return

    async def _handle_line(self, line: str) -> bool:
        """Normalize/filter one line and enqueue it; false means abort."""
        if self.strip_ansi:
            line = _strip_ansi(line)
        if self.read_only_tools is not None:
            violation = _check_tool_call(line, self.read_only_tools)
            if violation is not None:
                self._abort_child()
                await self._violations.put(violation)
                return False
        await self._enqueue_line(line)
        return True

    async def _finish_generation(
        self,
        process: subprocess.Popen[bytes],
    ) -> None:
        """Close and reap an EOF'd generation before auto-restarting."""
        self._close_stream(process.stdin)
        self._close_stream(process.stdout)
        preload_task = self._preload_task
        self._preload_task = None
        if preload_task is not None:
            try:
                await preload_task
            except (asyncio.CancelledError, Exception):
                pass
        try:
            await self._wait(process, timeout=0.2)
        except subprocess.TimeoutExpired:
            self._signal_process_group(process, signal.SIGTERM)
            try:
                await self._wait(process, timeout=0.1)
            except subprocess.TimeoutExpired:
                self._signal_process_group(process, signal.SIGKILL)
                await self._wait(process)
        if self._process is process:
            self._process = None
            self.pid = None

    async def _wait(
        self,
        process: subprocess.Popen[bytes],
        timeout: float | None = None,
    ) -> int:
        return await self._executor_submit(process.wait, timeout)

    async def stop(self) -> None:
        """Terminate the process group, reap the leader, and close all pipes."""
        if self._executor_shutdown:
            return
        if self._process is None and self._reader_task is None:
            # This also covers a Popen failure: executor work may have started
            # even though no process object was installed, so always close the
            # runner-owned pool when stop() is explicitly requested.
            self._stopping = True
            self._executor.shutdown(wait=True)
            self._executor_shutdown = True
            return

        self._stopping = True
        process = self._process
        self._process = None
        self.pid = None

        if process is not None:
            self._signal_process_group(process, signal.SIGTERM)
            self._close_stream(process.stdin)

        preload_task = self._preload_task
        self._preload_task = None
        if preload_task is not None and not preload_task.done():
            preload_task.cancel()
            try:
                await preload_task
            except (asyncio.CancelledError, BaseException):
                pass

        reader_task = self._reader_task
        self._reader_task = None
        if reader_task is not None and not reader_task.done():
            reader_task.cancel()
            try:
                await reader_task
            except (asyncio.CancelledError, BaseException):
                pass

        if process is not None:
            # Do not reap the leader before the escalation: while it remains a
            # child zombie its numeric PID/PGID cannot be reused for an
            # unrelated process. After the grace period, kill the whole group,
            # then let this Popen object perform the one canonical reap.
            await asyncio.sleep(0.1)
            self._signal_process_group(process, signal.SIGKILL)
            try:
                await self._wait(process)
            except ChildProcessError:
                pass
            self._close_stream(process.stdout)

        self._executor.shutdown(wait=True)
        self._executor_shutdown = True

    def _signal_process_group(
        self,
        process: subprocess.Popen[bytes],
        sig: signal.Signals,
    ) -> None:
        """Best-effort signal of the dedicated child group, then its leader."""
        pgid = process.pid
        if pgid and pgid != os.getpgrp():
            try:
                os.killpg(pgid, sig)
                return
            except (ProcessLookupError, PermissionError, OSError):
                pass
        try:
            process.send_signal(sig)
        except (ProcessLookupError, PermissionError, OSError):
            pass

    def _abort_child(self) -> None:
        process = self._process
        if process is not None:
            self._signal_process_group(process, signal.SIGTERM)

    @staticmethod
    def _close_stream(stream: BinaryIO | None) -> None:
        if stream is None:
            return
        try:
            stream.close()
        except (OSError, ValueError):
            pass

    async def _enqueue_line(self, line: str | None) -> None:
        """Apply bounded backpressure to the consumer-facing stream."""
        await self._lines.put(line)
