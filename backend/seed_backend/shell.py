"""Shell subprocess execution with piped, merged stdout and stderr.

ShellSession runs each expression in a fresh shell and uses private
files to carry the final cwd and OLDPWD across serialized calls.
"""
from __future__ import annotations

import asyncio
import os
import re
import signal
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path

from seed_backend.process_env import untrusted_child_env


# Strips CSI sequences (ESC [ ... final-byte). Good enough for
# the common cases (colors, cursor moves) — full ECMA-48 coverage
# is a future task if we ever need it.
_ANSI_CSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


# Output caps applied inside `exec_command`. Task 1.3: a runaway
# command (e.g. `cat /var/log/syslog`, `seq 1 1000000`) would
# happily fill gigabytes into our result and OOM the worker.
# Either cap being hit sets `ExecResult.truncated = True` and
# stops accumulating output (we still drain the PTY so the child
# doesn't block on a full slave buffer — we just throw the bytes
# away).
MAX_LINES: int = 5000
MAX_BYTES: int = 1 * 1024 * 1024  # 1 MiB


def strip_ansi(text: str) -> str:
    """Return `text` with common ANSI escape sequences removed.

    Used when the caller explicitly opts out of color codes via
    `capture_ansi=False`; clients that render to a TTY want the
    codes preserved, headless clients usually don't.
    """
    return _ANSI_CSI_RE.sub("", text)


class ExecCancelled(Exception):
    """Raised when an optional cancellation event aborts a command."""


@dataclass
class ExecResult:
    """Result of running a shell command.

    Attributes:
        stdout:        Captured output, decoded as UTF-8 (with
                       replacement for any undecodable bytes so a
                       single bad byte doesn't blow up the
                       request). With the PTY-backed executor
                       this is the merged output of stdout AND
                       stderr — a PTY is a single bidirectional
                       stream, so we can't tell them apart after
                       the fact. Kept named `stdout` for back-
                       compat with Task 1.1 callers.
        stderr:        Always empty for the PTY executor — see
                       the note on `stdout`. Kept on the dataclass
                       so the response model in `service.py`
                       doesn't have to change between task
                       versions.
        exit_code:     Process exit status. 0 means success; any
                       other value (1-255) is the exit code the
                       command returned. `-1` if the process was
                       killed by a signal we couldn't translate.
        captured_ansi: True if the output was preserved verbatim
                       (`capture_ansi=True`, the v0.1 default);
                       False if ANSI escape sequences were
                       stripped before returning. Lets the caller
                       know what they're getting.
        truncated:     True if the captured output hit the
                       `MAX_LINES` or `MAX_BYTES` cap defined in
                       this module; the caller is looking at a
                       prefix of the command's real output, not
                       the whole thing. False (the default) when
                       the command produced output within the
                       limits.
    """

    stdout: str
    stderr: str
    exit_code: int
    captured_ansi: bool
    truncated: bool = False


async def _cancel_consumer(cancel_evt: asyncio.Event) -> None:
    """Race the cancellation event against subprocess completion."""
    await cancel_evt.wait()
    raise ExecCancelled()


async def _exec_command_impl(
    cmd: str,
    *,
    cwd: str | None = None,
    timeout: float | None = None,
    capture_ansi: bool = True,
    cancel: asyncio.Event | None = None,
    shell_args: tuple[str, ...] | None = None,
    oldpwd: str | None = None,
) -> ExecResult:
    """Run a shell expression with merged stdout/stderr in a pipe.

    Popen is used for PRoot compatibility (fork/PTY is unavailable there).
    The process runs in its own group for timeout/cancellation cleanup.
    shell_args supplies positional arguments to sh -c for the session's
    private reporting files; oldpwd carries the previous directory.
    ANSI bytes written explicitly by commands are preserved by default.
    """
    loop = asyncio.get_running_loop()
    # Shared with the executor thread for process-group cleanup.
    if cancel is not None and cancel.is_set():
        raise ExecCancelled()
    state: dict = {"pid": None, "stop_signal": None}
    state_lock = threading.Lock()
    state_changed = threading.Condition(state_lock)
    stopped = threading.Event()

    def stop_child(sig: signal.Signals = signal.SIGKILL) -> None:
        # Serialize signaling with reaping: the unreaped leader pins its
        # PGID, and a stale numeric PID must never target an unrelated group.
        # A launch racing cancellation receives the recorded signal on publish.
        with state_changed:
            stopped.set()
            if state["stop_signal"] != signal.SIGKILL:
                state["stop_signal"] = sig
            if state["pid"] is not None:
                try:
                    os.killpg(state["pid"], state["stop_signal"])
                except (ProcessLookupError, PermissionError, OSError):
                    pass
            state_changed.notify_all()

    def _run_pty() -> tuple[bytes, int, bool]:
        # Fall back to subprocess.Popen (no PTY) because the
        # embedded Linux runtime runs inside proot, which does
        # not implement the `fork(2)` syscall (returns ENOSYS =
        # errno 38). `subprocess.Popen` on Linux uses
        # `posix_spawn` / `clone` and works inside proot. We
        # lose ANSI color support (no TTY for the child to
        # auto-detect), but the wire shape (`stdout`, `exit_code`,
        # `truncated`) is identical and the v0.1 route layer +
        # Android Shell tab don't care about color codes from the
        # embedded runtime — they're rendered as monospaced text
        # regardless.
        # Shell commands are untrusted local input and receive neither
        # provider credentials nor the control-plane capability.
        child_env = untrusted_child_env()
        child_env.pop("OLDPWD", None)
        if oldpwd is not None:
            child_env["OLDPWD"] = oldpwd
        if stopped.is_set() or (cancel is not None and cancel.is_set()):
            raise ExecCancelled()
        proc = subprocess.Popen(
            ["sh", "-c", cmd, "sh", *(shell_args or ())],
            cwd=cwd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,  # merge stderr into stdout, same as PTY
            stdin=subprocess.DEVNULL,
            start_new_session=True,  # so we can killpg on cancel/timeout
            env=child_env,
        )
        with state_lock:
            state["pid"] = proc.pid
            stop_signal = state["stop_signal"]
            must_stop = stopped.is_set() or (cancel is not None and cancel.is_set())
        if must_stop:
            stop_child(stop_signal or signal.SIGTERM)
        # Drain stdout in a read loop, applying the same
        # truncation policy as the PTY impl above. Note: no
        # master fd to close — we use proc.stdout.read instead
        # of os.read. This means there's no fd to "close to
        # unblock" on cancel; cancel/timeout must kill the
        # process (start_new_session ensures we can killpg).
        chunks: list[bytes] = []
        total_bytes = 0
        total_lines = 0
        truncated = False
        try:
            for data in iter(proc.stdout.readline, b""):
                if not data:
                    break
                if not truncated:
                    new_bytes = total_bytes + len(data)
                    new_lines = total_lines + data.count(b"\n")
                    if new_bytes > MAX_BYTES or new_lines > MAX_LINES:
                        truncated = True
                    else:
                        total_bytes = new_bytes
                        total_lines = new_lines
                        chunks.append(data)
        finally:
            try:
                proc.stdout.close()
            except OSError:
                pass
        # EOF need not imply leader exit (the shell may close its pipe).
        # Never block in wait() while holding the signaling lock. Poll with
        # WNOHANG under the lock so reaping and killpg cannot race; after
        # TERM, retain the unreaped leader/PGID until KILL reaches survivors.
        with state_changed:
            while True:
                if state["stop_signal"] == signal.SIGTERM:
                    state_changed.wait()
                    continue
                try:
                    exit_code = proc.wait(timeout=0)
                except subprocess.TimeoutExpired:
                    state_changed.wait(timeout=0.05)
                else:
                    state["pid"] = None
                    break
        return b"".join(chunks), exit_code, truncated

    exec_future = loop.run_in_executor(None, _run_pty)

    # Watcher task waits for the event. Runs
    # concurrently with the executor; the main coroutine races
    # them with `asyncio.wait` below.
    cancel_task: asyncio.Task[None] | None = None
    if cancel is not None:
        cancel_task = asyncio.create_task(_cancel_consumer(cancel))

    try:
        if cancel_task is None:
            output, exit_code, truncated = await asyncio.wait_for(
                asyncio.shield(exec_future),
                timeout=timeout,
            )
        else:
            # Race the executor against the cancel watcher, with
            # an overall timeout. Whichever finishes first wins:
            # the executor returning a result, the watcher
            # raising `ExecCancelled`, or `wait_for` itself firing
            # on timeout.
            done, pending = await asyncio.wait_for(
                asyncio.wait(
                    {exec_future, cancel_task},
                    return_when=asyncio.FIRST_COMPLETED,
                ),
                timeout=timeout,
            )
            # Cancel the loser of the race. If the cancel_task
            # lost, the executor's waitpid has already reaped the
            # child; otherwise the cancellation branch handles cleanup.
            if cancel_task in pending:
                cancel_task.cancel()
            if cancel_task in done:
                # Kill the process group and reap before returning the
                # cancellation to the caller.
                try:
                    await cancel_task
                except ExecCancelled:
                    stop_child(signal.SIGTERM)
                    # The pipe/leader can exit before the grace ends even
                    # though TERM-ignoring group members are still alive.
                    await asyncio.sleep(1.0)
                    stop_child(signal.SIGKILL)
                    try:
                        await asyncio.shield(exec_future)
                    except ExecCancelled:
                        pass  # cancelled before Popen
                    raise
                # Unreachable: cancel_task always raises
                # ExecCancelled. Kept as a defensive raise in
                # case the watcher is ever changed to return.
                raise ExecCancelled()
            # Executor won the race; pull the result off the
            # future (it's already done at this point).
            output, exit_code, truncated = exec_future.result()
    except asyncio.TimeoutError:
        # Stop any in-flight (or not-yet-published) child and wait for
        # the executor to reap before releasing session state.
        stop_child()
        if cancel_task is not None:
            cancel_task.cancel()
        try:
            await asyncio.shield(exec_future)
        except ExecCancelled:
            pass
        raise
    except asyncio.CancelledError:
        stop_child()
        if cancel_task is not None:
            cancel_task.cancel()
        try:
            await asyncio.shield(exec_future)
        except ExecCancelled:
            pass
        raise

    stdout = output.decode("utf-8", errors="replace")
    if not capture_ansi:
        stdout = strip_ansi(stdout)

    return ExecResult(
        stdout=stdout,
        stderr="",  # PTY: stdout and stderr share the slave fd.
        exit_code=exit_code,
        captured_ansi=capture_ansi,
        truncated=truncated,
    )


async def exec_command(
    cmd: str,
    *,
    timeout: float | None = None,
    capture_ansi: bool = True,
    cancel: asyncio.Event | None = None,
) -> ExecResult:
    """Stateless convenience wrapper around the subprocess executor.

    Task 1.5: with `ShellSession` now the recommended way to
    run commands (it gives you persistent cwd), the module-level
    `exec_command` stays around as a shortcut for callers that
    don't care about cwd — e.g. one-shot ad-hoc commands in
    tests. No `cwd` is passed, so the child inherits the
    orchestrator process's cwd (`Path.cwd()` at import time).
    """
    return await _exec_command_impl(
        cmd,
        cwd=None,
        timeout=timeout,
        capture_ansi=capture_ansi,
        cancel=cancel,
    )


# Open reporting descriptors before eval, then clear wrapper arguments so
# the expression sees ordinary `sh -c` positional semantics. A command may
# deliberately close/redirect fds 3/4 or overwrite shell variables; shell
# code with descriptor control cannot be made an isolated reporting channel.
# eval runs in this shell so pwd and OLDPWD reflect its final state.
_SESSION_WRAPPER = (
    '_seed_expression=$1; exec 3> "$2" 4> "$3"; set --; '
    'eval "$_seed_expression"; status=$?; '
    'command pwd -P >&3; printf %s "${OLDPWD-}" >&4; exit "$status"'
)


class ShellSession:
    """Shared per-process shell session; exec calls are serialized.

    Each expression uses a new sh process. Final cwd and OLDPWD are reported
    via private side-channel files, not user-visible stdout. An expression
    that exits its shell explicitly or is interrupted before reporting cannot
    update cwd. Other shell variables/functions do not persist. Client-specific
    sessions require a future authenticated session design.
    """

    def __init__(self, cwd: Path | None = None) -> None:
        self.cwd: Path = (cwd if cwd is not None else Path.cwd()).resolve()
        self._oldpwd: str | None = None
        self._lock = asyncio.Lock()

    async def exec(
        self,
        cmd: str,
        *,
        timeout: float | None = None,
        capture_ansi: bool = True,
        cancel: asyncio.Event | None = None,
    ) -> ExecResult:
        """Run the original shell expression and persist its final directory."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + timeout if timeout is not None else None
        if deadline is None:
            await self._lock.acquire()
        else:
            await asyncio.wait_for(self._lock.acquire(), max(0, deadline - loop.time()))
        try:
            if cancel is not None and cancel.is_set():
                raise ExecCancelled()
            remaining = max(0, deadline - loop.time()) if deadline is not None else None
            if remaining is not None and remaining <= 0:
                raise asyncio.TimeoutError()
            with tempfile.TemporaryDirectory(prefix="seed-shell-") as directory:
                cwd_file = Path(directory) / "cwd"
                oldpwd_file = Path(directory) / "oldpwd"
                result = await _exec_command_impl(
                    _SESSION_WRAPPER,
                    shell_args=(cmd, str(cwd_file), str(oldpwd_file)),
                    oldpwd=self._oldpwd,
                    cwd=str(self.cwd),
                    timeout=remaining,
                    capture_ansi=capture_ansi,
                    cancel=cancel,
                )
                # No report on timeout/cancellation/explicit shell exit. Never
                # infer state from user output or from a partially written file.
                if cwd_file.is_file() and oldpwd_file.is_file():
                    reported = cwd_file.read_text()
                    if reported.endswith("\n"):
                        reported = reported[:-1]
                    if reported and Path(reported).is_dir():
                        self.cwd = Path(reported)
                        self._oldpwd = oldpwd_file.read_text() or None
                return result
        finally:
            self._lock.release()

