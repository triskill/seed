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
import json
import os
import pty
import re
import signal
import tty
from pathlib import Path
from typing import AsyncIterator


# Strips common ANSI escape sequences from a line. Good
# enough for the common cases (colours, cursor moves,
# OSC titles); full ECMA-48 coverage is a future task
# if the chat UI ever needs it. Pattern matches:
#   CSI  ESC [ ... final-byte
#   OSC  ESC ] ... BEL or ESC \\
# Patterns live at module level so they're compiled once.
_ANSI_CSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")
_ANSI_OSC_RE = re.compile(r"\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)")


def _strip_ansi(text: str) -> str:
    """Return `text` with common ANSI escape sequences removed.

    Used by the reader task when `self.strip_ansi` is
    True (the default). Clients that render to a TTY
    want the codes preserved (flip the flag off); the
    chat UI doesn't, so we strip by default.
    """
    text = _ANSI_CSI_RE.sub("", text)
    text = _ANSI_OSC_RE.sub("", text)
    return text


def _check_tool_call(line: str, allowed: set[str]) -> dict | None:
    """If `line` is a `tool_execution_start` event for a
    disallowed tool, return a violation dict.

    Task 2.4. Returns None if the line isn't a tool
    event, or if the tool is allowed. Otherwise
    returns a dict shaped like the event, with the
    extra `"violation": True` key so the consumer can
    tell it apart from a normal event.

    The match is JSON-parse-and-check: we look for a
    line that parses as JSON with `type ==
    "tool_execution_start"` and a `toolName` field.
    Anything else (free-form text, a different event
    type, malformed JSON) is passed through to the
    consumer. We deliberately don't try to be clever
    with regex on the raw bytes — pi's wire format
    is JSONL and we should parse it as such.
    """
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
    if not isinstance(tool_name, str):
        return None
    if tool_name in allowed:
        return None
    return {"tool_name": tool_name, "event": event}


class PiRunnerNotRunning(RuntimeError):
    """Raised when send()/read_lines() is called before start().

    Distinct from a generic RuntimeError so the orchestrator
    layer can tell "you forgot to call start()" apart from
    other runtime errors and surface a useful message.
    """


class ToolCallBlocked(RuntimeError):
    """Raised when the child pi tries to call a disallowed tool.

    Task 2.4: the middle-man pi is supposed to be
    read-only — it can call `read`, `grep`, `find`, `ls`
    to inspect the user's web app, but never `bash`,
    `write`, or `edit` (which would mutate state). When
    the filter catches a disallowed tool call, it
    aborts the run (SIGTERM to the child) and surfaces
    this exception to the consumer of read_lines() so
    the chat UI can show "middle-man tried to do X,
    which it isn't allowed to".

    Attributes:
        tool_name: The tool that was blocked (e.g. "bash").
        event:     The full `tool_execution_start` event
                   we parsed. Carried on the exception so
                   the chat UI can show what the agent
                   was trying to do, not just the name.
    """

    def __init__(self, tool_name: str, event: dict) -> None:
        self.tool_name = tool_name
        self.event = event
        super().__init__(
            f"pi tried to call disallowed tool {tool_name!r}"
        )


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
        """
        Args:
            cmd:             The argv to exec. The first
                              element is the program; the
                              rest are arguments. Typically
                              `[sys.executable,
                              path/to/fake_pi.py]` in tests
                              and `["pi", "--mode", "rpc",
                              ...]` in production.
            env:             Optional environment dict for
                              the child. When set, the
                              runner calls `os.execvpe`
                              with this env; when None
                              (the default), it uses
                              `os.execvp` and inherits the
                              parent's environment. The
                              orchestrator passes a
                              derived env that overrides
                              `PI_CODING_AGENT_DIR` to
                              point at the project's
                              `.pi/agent/` so the child
                              uses our local config
                              (provider/model defaults,
                              no global pollution) while
                              still inheriting the API
                              key env var from the
                              parent shell.
            role:            Free-form string identifying
                              what this runner does
                              ("middleman" or "worker").
            strip_ansi:      When True (the default), ANSI
                              escape sequences are stripped
                              from every line yielded by
                              read_lines(). When False, the
                              raw bytes are passed through
                              unchanged.
            read_only_tools: When set, the runner watches
                              every line for a pi
                              `tool_execution_start` event
                              and aborts the run (raises
                              `ToolCallBlocked` from
                              read_lines()) if the event's
                              `toolName` is not in this set.
                              The default (None) means "no
                              filter" — every tool call is
                              allowed through, which is the
                              worker's configuration. The
                              middle-man passes the set of
                              read-only tools ({read, grep,
                              find, ls}); the worker passes
                              None. This is defense in
                              depth on top of the system
                              prompt and pi's `--tools` CLI
                              flag at spawn time.
            system_prompt:   Optional multi-line string
                              written to the child's stdin
                              on start() (Task 2.5). The
                              prompt is followed by a
                              blank line so the child can
                              tell where the prompt ends
                              and the user's first
                              message begins. Default ''
                              (no preload). The orchestrator
                              passes the contents of
                              `prompts/middleman.md` or
                              `prompts/worker.md` here.
            auto_restart:    When True (Task 2.6), the
                              runner respawns the child
                              if it dies (crash, OOM
                              kill, etc.) up to
                              `max_restarts` times. The
                              default is False so a
                              misconfigured orchestrator
                              doesn't loop; production
                              sets it to True.
            max_restarts:    Cap on respawn attempts
                              (Task 2.6) when
                              `auto_restart` is True. After
                              this many consecutive crashes
                              the runner gives up and
                              ends the read_lines() stream.
        """
        self.cmd = list(cmd)
        self.role = role
        self.env = dict(env) if env is not None else None
        self.strip_ansi = strip_ansi
        self.read_only_tools: set[str] | None = (
            set(read_only_tools) if read_only_tools is not None else None
        )
        self.system_prompt = system_prompt
        self.auto_restart = auto_restart
        self.max_restarts = max_restarts
        self._restart_count: int = 0
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
        # Violation queue: the reader task puts a
        # (tool_name, event) dict here when the tool-
        # call filter (Task 2.4) catches a disallowed
        # tool. The consumer of read_lines() pulls from
        # it and raises ToolCallBlocked. Bounded at 1
        # because the first violation aborts the run;
        # we don't need to track more.
        self._violations: asyncio.Queue[dict] = asyncio.Queue(maxsize=1)

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
        await self._do_fork()
        await self._do_preload()
        self._reader_task = asyncio.create_task(
            self._read_loop(), name="pi-runner-read"
        )

    async def _do_fork(self) -> None:
        """Fork a new child, set self.pid + self._master_fd.

        Used by start() and by the auto-restart path
        (Task 2.6) when the reader task sees EOF and
        decides to respawn. Same shape each time: open
        PTY, fork, set up child's session via setsid,
        report pgid back through a pipe, exec the cmd.
        """
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
                    if self.env is not None:
                        # `execvpe` takes the env as the
                        # 3rd arg. Use it when the runner
                        # was constructed with an explicit
                        # env (e.g. to point pi at the
                        # project's local config dir via
                        # `PI_CODING_AGENT_DIR`).
                        os.execvpe(self.cmd[0], self.cmd, self.env)
                    else:
                        # No explicit env — inherit the
                        # parent's. This is the pre-Phase-3
                        # default and what tests use (they
                        # spawn fake_pi, not pi, and don't
                        # care about config).
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

    async def _do_preload(self) -> None:
        """Write the system prompt to the child's stdin.

        Used by start() and by the auto-restart path
        (Task 2.6) so a respawned child gets the same
        prompt as the original. Raises RuntimeError if
        the child has already died (master fd closed)
        before we could write.
        """
        if not self.system_prompt:
            return
        if self._master_fd is None:
            return
        payload = self.system_prompt + "\n\n"
        try:
            await self._executor_submit(
                os.write, self._master_fd, payload.encode("utf-8")
            )
        except OSError as e:
            # Master fd already closed — child died
            # before we could write. Surface the
            # error so the caller knows the runner
            # isn't usable.
            raise RuntimeError(
                f"failed to preload system prompt: child died ({e})"
            ) from e

    async def _restart(self) -> bool:
        """Respawn the child after a crash (Task 2.6).

        Called by the reader task when it sees EOF on
        the master fd and `self.auto_restart` is True.
        Forks a new child, preloads the system prompt,
        and returns True. Returns False if we've
        already used up `self.max_restarts` (caller
        should treat that as "give up" and end the
        read_lines() stream).
        """
        if self._restart_count >= self.max_restarts:
            return False
        self._restart_count += 1
        # Reset child state. The previous child's pgid
        # is now meaningless (the process is gone).
        self._child_pgid = 0
        await self._do_fork()
        await self._do_preload()
        return True

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
        drains the master fd (signalled by a `None`
        sentinel; consumers should `async for line in
        runner.read_lines():` and stop iterating when
        they see `None`).

        Raises:
            PiRunnerNotRunning: if the runner hasn't been
                started.
            ToolCallBlocked: if the tool-call filter
                (Task 2.4) catches the child trying to
                call a tool outside its read-only set.
        """
        if self.pid is None:
            raise PiRunnerNotRunning(
                "PiRunner.read_lines() called before start()"
            )
        while True:
            # Yield to either the line queue or the
            # violation queue; whichever has data first
            # wins. We use asyncio.wait on a small set of
            # futures so the consumer can be interrupted
            # by a violation mid-stream.
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
            for t in pending:
                t.cancel()
            # Violation takes precedence: a tool call
            # the agent wasn't allowed to make is more
            # important than any in-flight text the
            # consumer might have been about to render.
            if get_violation in done:
                v = get_violation.result()
                raise ToolCallBlocked(v["tool_name"], v["event"])
            line = get_line.result()
            if line is None:
                # EOF sentinel from the reader task —
                # child closed its end. Stop iterating.
                return
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
            except OSError as e:
                # Two distinct EOF-ish conditions:
                #   1. EIO on a PTY master means the slave
                #      end was closed (the child exited or
                #      its controlling tty went away). This
                #      is the canonical "child is done"
                #      signal for a PTY-backed process.
                #   2. Any other OSError means the master
                #      fd itself was closed (stop() ran).
                # Either way, the read loop's job is done.
                if getattr(e, "errno", None) == 5:  # EIO
                    pass  # fall through to EOF handling
                else:
                    return
                chunk = b""
            if not chunk:
                # EOF: child closed the slave. Two paths
                # from here (Task 2.6):
                #   1. auto_restart=False (default): flush
                #      any partial line, signal EOF to the
                #      consumer, bail. The runner is done.
                #   2. auto_restart=True: try to respawn
                #      the child. If restart succeeds,
                #      continue the loop and read from the
                #      new master fd. If we've hit
                #      max_restarts, treat this as EOF.
                if buf:
                    await self._enqueue_line(buf)
                    buf = ""
                if self.auto_restart and self.pid is not None:
                    # The old child exited; clear pid so
                    # stop() doesn't try to waitpid the
                    # wrong pid later. _restart() sets
                    # self.pid to the new child's pid.
                    old_pid = self.pid
                    self.pid = None
                    if await self._restart():
                        # New child is up. Continue the
                        # loop; the next iteration will
                        # read from the new master fd.
                        continue
                    # Restart failed (max_restarts
                    # exceeded). Fall through to EOF
                    # handling.
                    self.pid = old_pid
                await self._enqueue_line(None)  # EOF
                return
            buf += chunk.decode("utf-8", errors="replace")
            # Split on LF; keep partial trailing data in buf.
            while "\n" in buf:
                line, _, buf = buf.partition("\n")
                # Strip CR if the child emitted \r\n (some
                # TTY configurations still do).
                if line.endswith("\r"):
                    line = line[:-1]
                if self.strip_ansi:
                    line = _strip_ansi(line)
                # Tool-call filter (Task 2.4). Only acts
                # when read_only_tools is set; otherwise
                # every line is yielded verbatim. When the
                # filter fires, we abort the child via
                # SIGTERM (so it can't keep mutating state
                # in the background after the consumer
                # gives up) and signal the violation back
                # to the consumer by putting the event on
                # a separate "violation" queue. The
                # consumer of read_lines() pulls that
                # off and raises ToolCallBlocked.
                if self.read_only_tools is not None:
                    violation = _check_tool_call(line, self.read_only_tools)
                    if violation is not None:
                        # Don't yield the offending line to
                        # the consumer — yielding it would
                        # look like the call succeeded.
                        # Signal the child to stop; the
                        # child is in its own process group
                        # (we used setsid in start()), so
                        # killpg targets only it.
                        self._abort_child()
                        await self._violations.put(violation)
                        # Drain any further lines (shouldn't
                        # be many before the child exits)
                        # and return to the caller; the
                        # consumer's read_lines() loop will
                        # pick up the violation next.
                        return
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

    def _abort_child(self) -> None:
        """Send SIGTERM to the child process group.

        Used by the tool-call filter to stop a
        middle-man that's about to do something it
        shouldn't. Best-effort; if the child is
        already gone or setsid() failed, the call is
        a no-op. We deliberately don't waitpid here
        — the caller (the read loop) is going to
        return and let stop() handle the reaping
        (so the orchestration path stays linear).
        """
        if self._child_pgid and self._child_pgid != os.getpid():
            try:
                os.killpg(self._child_pgid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        elif self.pid is not None:
            try:
                os.kill(self.pid, signal.SIGTERM)
            except (ProcessLookupError, PermissionError, OSError):
                pass
