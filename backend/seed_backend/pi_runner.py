"""PiRunner: PTY-backed wrapper around the `pi` CLI.

Task 2.2: spawns the `pi` child process in a PTY, exposes
`send()` to write a user message to the master fd, and
`read_lines()` as an async generator that yields one
newline-terminated line at a time. ANSI stripping, tool
filtering, system-prompt preload, and auto-restart are
added in Tasks 2.3-2.6 — this module only owns the
PTY lifecycle and the read/write plumbing.

Why a PTY? `pi` is a TUI Node.js app. Programs that
auto-detect TTY (and `pi`'s renderer is one of them)
emit different output depending on whether they see a
real terminal. Wrapping the child in a PTY means we get
the same behaviour the user would see in a shell, even
though we're driving it programmatically. The trade-off
is that we have to put the PTY in raw mode and buffer
our own lines (no TTY line discipline to lean on).

The fork+exec sequence runs in a dedicated thread pool
executor owned by the runner (one per PiRunner
instance). The default asyncio executor is shared across
the whole process and gets into weird states when
multiple runners are torn down in sequence (the worker
threads inherit an inconsistent user-space view from
the parent at fork time, which can lead to hangs). A
per-runner executor is shut down deterministically in
stop() — the worker threads exit cleanly, the process
stays responsive, and tests don't hang on interpreter
teardown.

Design note: there is exactly one place in this module
that calls `os.waitpid` — `stop()`. The first version
also had a background "waiter" task that called waitpid
to detect child exit, but two threads in `os.waitpid(pid,
0)` for the same pid is a footgun: the kernel only
delivers the exit to ONE of them, the other blocks
forever. So the read loop is the canonical signal for
"child is gone" (it sees EOF on the master fd), and
stop() is the canonical reaper. This keeps the design
linear: start -> read -> stop, with stop the only
thing that touches the child's exit status.
"""
from __future__ import annotations

import asyncio
import concurrent.futures
import os
import pty
import signal
import tty
from pathlib import Path
from typing import AsyncIterator


class PiRunnerNotRunning(RuntimeError):
    """Raised when send()/read_lines() is called before start().

    Distinct from a generic RuntimeError so the orchestrator
    layer can tell "you forgot to call start()" apart from
    other runtime errors and surface a useful message.
    """


class PiRunner:
    """PTY-backed wrapper around a `pi`-like CLI subprocess.

    The runner is a thin orchestrator: it owns the child
    process and its PTY, exposes a send() call to write
    user input, and yields child output line-by-line via
    the read_lines() async generator. It does NOT
    interpret the output — that's the route layer's job
    (later tasks will dispatch JSON events to chat cards,
    detect tool calls, etc.). The runner just shovels
    bytes and lines.

    Attributes:
        cmd:     The argv to exec. The first element is the
                 program; the rest are arguments. Typically
                 `[sys.executable, path/to/fake_pi.py]` in
                 tests and `["pi", "--mode", "rpc", ...]` in
                 production.
        role:    Free-form string identifying what this
                 runner does ("middleman" or "worker"). The
                 tool-call filter (Task 2.4) consults this
                 to decide which pi tool calls to allow.
        pid:     The child's PID after start() has been
                 awaited; None before start() and after
                 stop() has fully cleaned up.

    Lifecycle:
        runner = PiRunner(cmd, role)
        await runner.start()
        await runner.send("user prompt")
        async for line in runner.read_lines():
            ...
        await runner.stop()
    """

    def __init__(self, cmd: list[str], role: str) -> None:
        self.cmd = list(cmd)
        self.role = role
        self.pid: int | None = None
        # Filled in by the executor thread after the PTY is
        # opened / fork returns. Read by send() and read_lines()
        # from the asyncio thread. CPython's GIL makes
        # single-int writes atomic, no extra lock needed.
        self._master_fd: int | None = None
        # Backpressure for read_lines(): every newline-terminated
        # chunk is enqueued here; the async generator awaits
        # the next item. Bounded so a misbehaving child can't
        # OOM the orchestrator.
        self._lines: asyncio.Queue[str] = asyncio.Queue(maxsize=1024)
        # Reader task that pulls bytes from the master fd and
        # splits them on LF. Created in start(), cancelled in
        # stop(). When this task ends (EOF or master fd
        # closed), the child is gone or about to be reaped by
        # stop().
        self._reader_task: asyncio.Task[None] | None = None
        # Per-runner thread pool. Owned by this instance,
        # shut down deterministically in stop() so worker
        # threads don't outlive the runner and don't
        # interfere with subsequent runners that fork.
        # 4 workers is enough: fork, read, send, waitpid
        # (the last two can overlap with read).
        self._executor: concurrent.futures.ThreadPoolExecutor = (
            concurrent.futures.ThreadPoolExecutor(
                max_workers=4,
                thread_name_prefix=f"pi-runner-{role}",
            )
        )
        # The child's process group id, as reported by the
        # child itself right after setsid(). 0 if the
        # child failed to setsid() (pytest's multi-threaded
        # environment can make setsid fail silently) —
        # stop() then falls back to a direct kill on the
        # pid. Filled in by the fork helper in start().
        self._child_pgid: int = 0

    async def start(self) -> None:
        """Spawn the child in a PTY. No-op if already started.

        The child gets a fresh session (`os.setsid` in the
        fork child) so the runner can kill the whole
        process group on shutdown — `pi` may spawn helper
        processes (e.g. a renderer subprocess) that would
        outlive a leader-only kill.

        Raises:
            OSError: if the fork or PTY open fails.
        """
        if self.pid is not None:
            return

        def _fork() -> int:
            # Identity pipe: child writes its post-setsid
            # pgid here so the parent can killpg the right
            # group. (os.getpgid(child_pid) from the parent
            # is the canonical answer in theory, but in
            # pytest's multi-threaded context setsid can
            # silently fail in the child, leaving it in
            # the parent's pgid — the pipe lets us detect
            # that and fall back to a direct kill.)
            id_read, id_write = os.pipe()

            master_fd, slave_fd = pty.openpty()
            # Raw mode: no OPOST (so no \n -> \r\n translation)
            # and no ICANON (so we get bytes immediately
            # rather than line-buffered input). The reader
            # task below splits on LF manually.
            tty.setraw(master_fd)
            pid = os.fork()
            if pid == 0:
                # Child branch. Any exception here would
                # leave us in a half-set-up state, so we
                # _exit on errors instead of raising.
                os.close(master_fd)
                os.close(id_read)
                try:
                    os.setsid()
                except OSError:
                    # If setsid fails, child_pgid == 0
                    # signals "kill by pid, not pgid".
                    pass
                # Report our actual pgid to the parent.
                # (os.getpgid(0) is equivalent to
                # os.getpgid(os.getpid()) in the child.)
                try:
                    child_pgid = os.getpgid(0)
                except OSError:
                    child_pgid = 0
                try:
                    os.write(id_write, str(child_pgid).encode() + b"\n")
                except OSError:
                    pass
                os.close(id_write)
                try:
                    os.dup2(slave_fd, 0)
                    os.dup2(slave_fd, 1)
                    os.dup2(slave_fd, 2)
                finally:
                    os.close(slave_fd)
                try:
                    os.execvp(self.cmd[0], self.cmd)
                except OSError:
                    os._exit(127)
            # Parent branch.
            os.close(slave_fd)
            os.close(id_write)
            self._master_fd = master_fd
            # Read the child's reported pgid (blocks until
            # child execs; tiny window where child is
            # between fork and setsid is handled by the
            # try/except in the child).
            try:
                data = os.read(id_read, 64)
            finally:
                os.close(id_read)
            try:
                self._child_pgid = int(data.strip()) if data else 0
            except ValueError:
                self._child_pgid = 0
            return pid

        # fork() in a thread — same reason shell.py does it.
        # The DeprecationWarning is expected and harmless
        # (Task 1.5's known limitation).
        self.pid = await self._executor_submit(_fork)

        # Only the reader task — there is intentionally no
        # background "waiter" task. os.waitpid is a global
        # resource (only one waiter per child); having two
        # threads blocked in waitpid for the same pid is a
        # footgun (one wins, the other blocks forever). The
        # reader task sees EOF on the master fd when the
        # child exits, which is the canonical "child is
        # gone" signal; stop() is the canonical reaper.
        self._reader_task = asyncio.create_task(
            self._read_loop(), name="pi-runner-read"
        )

    async def send(self, message: str) -> None:
        """Write a user message to the child's PTY stdin.

        A trailing newline is appended if the message
        doesn't end with one — `pi` (and the fake pi) read
        one line of input per turn. Raises
        PiRunnerNotRunning if the runner hasn't been
        started (or has been stopped).
        """
        if self.pid is None or self._master_fd is None:
            raise PiRunnerNotRunning("PiRunner.send() called before start()")
        payload = message if message.endswith("\n") else message + "\n"
        await self._executor_submit(os.write, self._master_fd, payload.encode("utf-8"))

    async def _executor_submit(self, fn, *args):
        """Submit `fn(*args)` to the runner's executor, await the result.

        Thin wrapper so every run_in_executor call goes
        through one place. The default executor
        (`loop.run_in_executor(None, ...)`) is a process-
        wide singleton; for the runner we want a private
        pool we can shut down deterministically.
        """
        loop = asyncio.get_running_loop()
        return await loop.run_in_executor(self._executor, fn, *args)

    async def read_lines(self) -> AsyncIterator[str]:
        """Async generator that yields child output, one line at a time.

        Each yielded string is a single line of child output
        with the trailing newline stripped. Empty lines
        are preserved (a child that writes two consecutive
        newlines produces two empty yields). The generator
        terminates when the child exits and the reader
        drains the master fd.

        Raises:
            PiRunnerNotRunning: if the runner hasn't been
                started.
        """
        if self.pid is None:
            raise PiRunnerNotRunning(
                "PiRunner.read_lines() called before start()"
            )
        while True:
            line = await self._lines.get()
            yield line

    async def stop(self) -> None:
        """Terminate the child and reap it. Idempotent.

        Sequence:
          1. SIGTERM the process group.
          2. Cancel the reader task; close the master fd so
             the reader thread's os.read returns EBADF.
          3. Wait up to 100ms for the child to exit.
          4. SIGKILL the process group as a fallback.
          5. waitpid (blocking, in a thread) — the ONLY
             call to os.waitpid in this module. This is
             why there is no background waiter task: two
             threads in os.waitpid for the same pid would
             be a footgun.
          6. Shutdown the default executor so worker
             threads don't keep the process alive
             (matters in tests; production processes run
             forever and re-create the executor on next
             use).
        Safe to call multiple times; subsequent calls are
        no-ops.
        """
        if self.pid is None:
            return

        pid = self.pid
        self.pid = None

        # 1. Best-effort SIGTERM. Use the child's self-
        # reported pgid (filled in by the fork helper).
        # If setsid() failed in the child the pgid is 0
        # and we fall back to a direct kill on the pid.
        if self._child_pgid and self._child_pgid != os.getpid():
            try:
                os.killpg(self._child_pgid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        else:
            try:
                os.kill(pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                pass

        # 2. Cancel the reader task; close the master fd so
        # the reader thread's os.read returns EBADF.
        reader_task = self._reader_task
        self._reader_task = None
        if reader_task is not None and not reader_task.done():
            reader_task.cancel()
            try:
                await reader_task
            except (asyncio.CancelledError, BaseException):
                pass
        if self._master_fd is not None:
            try:
                os.close(self._master_fd)
            except OSError:
                pass
            self._master_fd = None

        # 3. Grace period.
        await asyncio.sleep(0.1)

        # 4. SIGKILL fallback.
        if self._child_pgid and self._child_pgid != os.getpid():
            try:
                os.killpg(self._child_pgid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        else:
            try:
                os.kill(pid, signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        try:
            os.killpg(os.getpgid(pid), signal.SIGKILL)
        except (ProcessLookupError, PermissionError, OSError):
            pass

        # 5. Reap. The blocking call goes in a thread so
        # the event loop stays responsive; in practice
        # the child is already dead by now, so waitpid
        # returns immediately.
        try:
            await self._executor_submit(os.waitpid, pid, 0)
        except ChildProcessError:
            pass

        # 6. Shut down the runner's own executor. This is
        # what makes stop() safe to call repeatedly and
        # safe to call from a teardown path: the worker
        # threads we own exit cleanly (no zombie threads
        # keeping the process alive, no stale state for
        # the next fork to inherit). Subsequent
        # run_in_executor calls would fail; the runner
        # is meant to be single-use.
        self._executor.shutdown(wait=True)

    async def _read_loop(self) -> None:
        """Pull bytes from the master fd and split into lines.

        Runs as a long-lived asyncio task. Uses a thread
        for the blocking `os.read` so the event loop stays
        unblocked. The line buffer is per-call (small);
        large outputs are fine because the thread reads in
        4096-byte chunks and the queue has a 1024-item
        cap (the read loop blocks on `put` if the consumer
        is slow, which is the desired backpressure).
        """
        buf = ""
        while True:
            master_fd = self._master_fd
            if master_fd is None:
                return
            try:
                chunk = await self._executor_submit(os.read, master_fd, 4096)
            except OSError:
                # Master fd closed (stop() ran) — clean exit.
                return
            if not chunk:
                # EOF: child closed the slave. Flush any
                # trailing partial line and bail.
                if buf:
                    await self._enqueue_line(buf)
                    buf = ""
                return
            buf += chunk.decode("utf-8", errors="replace")
            # Split on LF; keep partial trailing data in buf.
            while "\n" in buf:
                line, _, buf = buf.partition("\n")
                # Strip CR if the child emitted \r\n (some
                # TTY configurations still do).
                if line.endswith("\r"):
                    line = line[:-1]
                await self._enqueue_line(line)

    async def _enqueue_line(self, line: str) -> None:
        """Put a line on the consumer queue, blocking if full.

        Backpressure: if the consumer (the route layer)
        is slow, the read loop blocks here instead of
        growing a buffer unbounded. Worst case the master
        fd's slave buffer fills, the child's `write` blocks,
        and the child stalls — exactly the behaviour we
        want (don't OOM the orchestrator).
        """
        await self._lines.put(line)
