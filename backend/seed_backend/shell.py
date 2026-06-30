"""Host-side shell subprocess execution.

Task 1.1 added the smallest useful `exec_command`: a subprocess
with piped stdout/stderr. Task 1.2 swaps that for a PTY-backed
implementation so ANSI color codes are preserved end-to-end —
programs like `ls --color=auto` (and anything else that auto-
detects TTY, e.g. `git`, `diff`, `ls`) only emit color escapes
when they detect a terminal, and a PIPE is not a terminal.

Task 1.5 wraps the executor in a `ShellSession` class that
remembers a per-session working directory. v0.1 is a heuristic:
when a command starts with `cd <path>`, we resolve and update
`cwd` ourselves, then run whatever's left. There's no real
persistent shell process — every `exec` is a fresh `sh -c`
subprocess — so the heuristic is a thin convention on top of
the existing PTY executor. Limitations spelled out on
`ShellSession`.

Security note: this orchestrator is intended to run on a trusted
LAN with the Android client as the only caller; the endpoint is
unauthenticated by design for v0.1.
"""
from __future__ import annotations

import asyncio
import os
import pty
import re
import signal
import tty
from dataclasses import dataclass
from pathlib import Path


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
    """Raised when an in-flight `exec_command` is cancelled by its caller.

    Task 1.4: the caller passes an `asyncio.Event` as the `cancel`
    argument. When the event is set, the executor sends SIGTERM
    to the child's process group, closes the PTY master fd to
    unblock the read loop, waits a brief grace period, then
    SIGKILLs the process group as a fallback before raising
    this exception. Distinct from `asyncio.TimeoutError` so the
    route layer (and tests) can tell the two apart.
    """


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


async def _cancel_consumer(
    cancel_evt: asyncio.Event | None,
    state: dict,
) -> None:
    """Watcher coroutine that kills the child when the user sets `cancel_evt`.

    Task 1.4: lives alongside the executor and races it via
    `asyncio.wait`. Three outcomes:

    * `cancel_evt is None` — the user didn't ask for cancellation.
      Block forever; the main coroutine cancels us once the
      executor returns. (We never raise.)
    * `cancel_evt` is set before we get cancelled by the main
      coroutine — send SIGTERM, close the master fd, then raise
      `ExecCancelled` so the race in the main coroutine surfaces
      it. The main coroutine then waits a 1 s grace period and
      SIGKILLs the process group as a fallback (in case SIGTERM
      was ignored — `sleep` does this by default). The split is
      deliberate: raising immediately after the SIGTERM means
      `asyncio.wait(..., FIRST_COMPLETED)` sees the cancel_task
      as done first, before the executor thread's `waitpid` can
      return and the executor's future can win the race.
    * We get cancelled (because the executor won the race) —
      the executor's `waitpid` has already reaped the child, so
      there's nothing to clean up; just return.

    Best-effort error handling throughout: the OS may already
    have cleaned things up, and we don't care.
    """
    if cancel_evt is None:
        # Block forever; main coroutine cancels us on success.
        try:
            await asyncio.Event().wait()
        except asyncio.CancelledError:
            return
        return

    try:
        await cancel_evt.wait()
    except asyncio.CancelledError:
        # Executor won the race; its waitpid already reaped
        # the child, so nothing to clean up.
        return

    # User signalled cancel. Kill the child process group (the
    # child + any helpers it spawned, like the `sleep 60` child
    # of `sh -c "sleep 60"`) and close the master fd so the
    # in-flight `os.read` in the executor thread unblocks. The
    # grace period + SIGKILL fallback happens in the main
    # coroutine's exception handler — see `exec_command`.
    pid = state.get("pid")
    if pid is not None:
        try:
            os.killpg(os.getpgid(pid), signal.SIGTERM)
        except (ProcessLookupError, PermissionError, OSError):
            pass
    master_fd = state.get("master_fd")
    if master_fd is not None:
        try:
            os.close(master_fd)
        except OSError:
            pass

    raise ExecCancelled()


async def _exec_command_impl(
    cmd: str,
    *,
    cwd: str | None = None,
    timeout: float | None = None,
    capture_ansi: bool = True,
    cancel: asyncio.Event | None = None,
) -> ExecResult:
    """Run `cmd` via `sh -c` under a PTY and return the captured output.

    The command is passed to `sh -c`, so shell features (pipes,
    `&&`, glob expansion, etc.) work. The command runs with its
    stdin/stdout/stderr all attached to the slave end of a
    freshly-opened PTY; we read from the master end until the
    child exits. Because the slave looks like a terminal to the
    child, programs that auto-detect TTY (e.g. `ls --color=auto`)
    emit color escape sequences — which is the whole point of
    this task.

    The PTY is put into raw mode (OPOST off, ICANON off) before
    the child execs, so output bytes come through verbatim.
    Without that, the line discipline would turn every `\\n` into
    `\\r\\n` and a plain `echo hi` would arrive as `hi\\r\\n`,
    breaking every test that asserts a `hi\\n` shape.

    stderr is NOT separate from stdout in this model: both fd 1
    and fd 2 are dup2'd onto the same slave fd, so anything the
    command writes to either ends up interleaved in the same
    stream. `result.stderr` is therefore always `""`.

    On timeout, the child is its own session leader (via
    `os.setsid`), so we can `killpg` the entire group. Some
    shells spawn helpers — `sh -c "sleep 5"` is the canonical
    example — and killing only the leader would leave the
    helpers running.

    The fork+wait+read sequence is blocking, so it runs in the
    default thread-pool executor via `run_in_executor`. The
    timeout handler then needs a way to kill the in-flight
    child, so we pass a small shared dict that the executor
    thread fills in with the child pid and master fd as soon as
    the fork returns.

    Args:
        cmd:          The shell command to run.
        cwd:          Optional working directory for the subprocess.
        timeout:      Optional wall-clock timeout in seconds. On
                      expiry, the child's process group is
                      SIGKILL'd and `asyncio.TimeoutError` is
                      re-raised.
        capture_ansi: If True (default), ANSI escape sequences in
                      the output are preserved. If False, they
                      are stripped via `strip_ansi` before
                      returning.
        cancel:       Optional `asyncio.Event` the caller can
                      set to abort the in-flight command. On
                      set, the child's process group is
                      SIGTERM'd (followed by SIGKILL after a
                      1-second grace period) and `ExecCancelled`
                      is raised. The route in `service.py`
                      doesn't currently wire this up — the
                      capability lives on the function for
                      future use (e.g. aborting on client
                      disconnect).

    Returns:
        ExecResult with decoded merged stdout, an empty stderr,
        the exit code, and a `captured_ansi` flag mirroring the
        input.
    """
    loop = asyncio.get_running_loop()
    # Shared between the executor thread and the asyncio event
    # loop thread. The thread writes `pid` after forking and
    # `master_fd` after opening the PTY; the timeout handler
    # reads them to send SIGKILL to the process group and to
    # close the master fd so the in-flight read unblocks.
    # CPython's GIL makes plain dict[int|None] writes atomic, so
    # no extra locking is needed.
    state: dict = {"pid": None, "master_fd": None}

    def _run_pty() -> tuple[bytes, int, bool]:
        master_fd, slave_fd = pty.openpty()
        # Raw mode on the PTY: OPOST off means no \n → \r\n
        # translation, ICANON off means no input line buffering.
        # Without this, `echo hi` arrives as `hi\r\n` and the
        # existing tests break.
        tty.setraw(master_fd)
        state["master_fd"] = master_fd

        pid = os.fork()
        if pid == 0:
            # Child branch. From here on, any `raise` would leave
            # the child in a half-set-up state, so we use
            # `os._exit` on errors instead of propagating.
            os.close(master_fd)
            # New session: child is its own session leader, so
            # the parent can `killpg` the whole group on timeout
            # (helpers like `sleep` under `sh -c "sleep 5"` would
            # otherwise survive a leader-only kill).
            os.setsid()
            try:
                os.dup2(slave_fd, 0)
                os.dup2(slave_fd, 1)
                os.dup2(slave_fd, 2)
            finally:
                os.close(slave_fd)
            if cwd is not None:
                try:
                    os.chdir(cwd)
                except OSError:
                    os._exit(127)
            try:
                os.execvp("sh", ["sh", "-c", cmd])
            except OSError:
                os._exit(127)

        # Parent branch.
        state["pid"] = pid
        os.close(slave_fd)

        chunks: list[bytes] = []
        total_bytes = 0
        total_lines = 0
        truncated = False
        try:
            while True:
                try:
                    data = os.read(master_fd, 4096)
                except OSError:
                    # Master fd was closed (e.g. by the timeout
                    # handler), so the child is gone — bail.
                    break
                if not data:
                    break
                if not truncated:
                    new_bytes = total_bytes + len(data)
                    new_lines = total_lines + data.count(b"\n")
                    if new_bytes > MAX_BYTES or new_lines > MAX_LINES:
                        # Hit the cap. We still fall through to
                        # the next iteration to keep draining the
                        # PTY — otherwise the child's `write`
                        # would block once the slave buffer fills
                        # and we'd deadlock — but we throw the
                        # bytes away instead of accumulating.
                        truncated = True
                    else:
                        total_bytes = new_bytes
                        total_lines = new_lines
                        chunks.append(data)
        finally:
            try:
                os.close(master_fd)
            except OSError:
                pass
            state["master_fd"] = None

        # Reap the child. waitpid shouldn't fail here short of
        # the child already being reaped under us, in which case
        # we return whatever we already read with exit_code=-1.
        try:
            _, status = os.waitpid(pid, 0)
        except ChildProcessError:
            return b"".join(chunks), -1, truncated
        if os.WIFEXITED(status):
            exit_code = os.WEXITSTATUS(status)
        elif os.WIFSIGNALED(status):
            exit_code = 128 + os.WTERMSIG(status)
        else:
            exit_code = -1

        return b"".join(chunks), exit_code, truncated

    exec_future = loop.run_in_executor(None, _run_pty)

    # Watcher task: blocks forever if no user cancel event,
    # otherwise waits for the event and kills the child. Runs
    # concurrently with the executor; the main coroutine races
    # them with `asyncio.wait` below.
    cancel_task: asyncio.Task[None] | None = None
    if cancel is not None:
        cancel_task = asyncio.create_task(_cancel_consumer(cancel, state))

    try:
        if cancel_task is None:
            output, exit_code, truncated = await asyncio.wait_for(
                exec_future,
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
            # child; if the executor lost, the cancel_task's
            # SIGTERM-and-close is already in flight and we'll
            # let the rest of the cleanup happen here.
            for t in pending:
                t.cancel()
            if cancel_task in done:
                # The cancel_task sent SIGTERM and closed the
                # master fd before raising. Re-raise the
                # exception for the caller, but first do
                # best-effort cleanup: SIGTERM is advisory and
                # some commands ignore it (e.g. `sleep 60`),
                # so wait a brief grace period then SIGKILL the
                # process group as a fallback.
                pid = state.get("pid")
                try:
                    await cancel_task
                except BaseException:
                    if pid is not None:
                        try:
                            await asyncio.sleep(1.0)
                        except asyncio.CancelledError:
                            pass
                        try:
                            os.killpg(os.getpgid(pid), signal.SIGKILL)
                        except (ProcessLookupError, PermissionError, OSError):
                            pass
                    raise
                # Unreachable: cancel_task always raises
                # ExecCancelled. Kept as a defensive raise in
                # case the watcher is ever changed to return.
                raise ExecCancelled()
            # Executor won the race; pull the result off the
            # future (it's already done at this point).
            output, exit_code, truncated = exec_future.result()
    except asyncio.TimeoutError:
        # Best-effort cleanup: SIGKILL the whole process group
        # (the child + any helpers it spawned, like the `sleep 5`
        # child of `sh -c "sleep 5"`), close the master fd so the
        # in-flight `os.read` in the executor thread unblocks,
        # and cancel the cancel watcher if it's still parked on
        # `cancel_evt.wait()`. We don't await the executor future
        # here — it's still running in a background thread that
        # will clean up after itself (and asyncio would warn
        # about the result being discarded, which is the desired
        # behaviour: we already have a TimeoutError to raise).
        pid = state.get("pid")
        if pid is not None:
            try:
                os.killpg(os.getpgid(pid), signal.SIGKILL)
            except (ProcessLookupError, PermissionError, OSError):
                pass
        master_fd = state.get("master_fd")
        if master_fd is not None:
            try:
                os.close(master_fd)
            except OSError:
                pass
        if cancel_task is not None and not cancel_task.done():
            cancel_task.cancel()
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
    """Stateless convenience wrapper around the PTY executor.

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


# Matches a leading `cd <path>` (with optional whitespace) at the
# start of a command. Group 1 is the target path; group 2 (optional)
# is whatever follows on the same line. Used by `ShellSession` to
# update its `cwd` heuristically — see that class for the full
# rationale and limitations.
_CD_LEAD_RE = re.compile(r"^\s*cd\s+(\S+)(?:\s+(.*))?$", re.DOTALL)


class ShellSession:
    """Per-session shell executor with a persistent working directory.

    v0.1 heuristic (NOT a real persistent shell process):
    every `exec` call still spawns a fresh `sh -c` subprocess via
    the PTY executor; the cwd is threaded through as the
    subprocess's working directory and remembered on the session
    for the next call. To make commands like `cd /tmp` actually
    take effect, we sniff the command for a leading `cd <path>`
    (regex `_CD_LEAD_RE`), resolve the target against the
    current cwd, verify it exists and is a directory, and
    update `self.cwd` accordingly. Whatever's left after the
    `cd` is what gets handed to `sh -c`.

    Known limitations (acceptable for v0.1):
      * Only a leading `cd <path>` is recognized. `cd /tmp &&
        ls` updates cwd correctly but the `&&` syntax isn't
        stripped — the executor will see `&& ls` and `sh -c`
        will reject it. Use `cd /tmp; ls` (or two separate
        `exec` calls) instead.
      * `cd -` (previous dir), `cd ~` (tilde expansion), and
        `pushd`/`popd` are NOT recognized. The path is resolved
        verbatim by `Path.resolve()`.
      * If the `cd` target doesn't exist or isn't a directory,
        we fall back to running the original command unchanged
        and let `sh -c` fail naturally; `self.cwd` is not
        updated. This is a "do no harm" choice — the user
        sees the underlying `sh` error, not a Python exception
        from us.

    Attributes:
        cwd: Current working directory for the session. Updated
             by the `cd` heuristic in `exec`; mutable so tests
             and the route layer can inspect it.
    """

    def __init__(self, cwd: Path | None = None) -> None:
        self.cwd: Path = (cwd if cwd is not None else Path.cwd()).resolve()

    async def exec(
        self,
        cmd: str,
        *,
        timeout: float | None = None,
        capture_ansi: bool = True,
        cancel: asyncio.Event | None = None,
    ) -> ExecResult:
        """Run `cmd` in the session's cwd; honour a leading `cd`.

        See the class docstring for the heuristic and its limits.
        Returns an empty `ExecResult` (exit_code=0) when the
        command is just `cd <path>` with no further work; the
        cwd has already been updated in that case.
        """
        run_cmd, updated = self._apply_cd(cmd)
        if updated and run_cmd == "":
            # Pure `cd <path>` with no follow-on command. We've
            # already validated and updated cwd; return a
            # successful empty result so the caller doesn't
            # have to special-case it.
            return ExecResult(
                stdout="",
                stderr="",
                exit_code=0,
                captured_ansi=capture_ansi,
                truncated=False,
            )
        return await _exec_command_impl(
            run_cmd,
            cwd=str(self.cwd),
            timeout=timeout,
            capture_ansi=capture_ansi,
            cancel=cancel,
        )

    def _apply_cd(self, cmd: str) -> tuple[str, bool]:
        """Return `(cmd_to_run, cwd_was_updated)`.

        If `cmd` starts with `cd <path>`, attempt to resolve the
        target against `self.cwd`. On success, update `self.cwd`
        and return the remainder of the command (or `""` if
        there was no remainder). On any failure (bad path,
        non-directory, regex miss), return the original command
        unchanged and `cwd_was_updated=False`.
        """
        m = _CD_LEAD_RE.match(cmd)
        if m is None:
            return cmd, False
        target = m.group(1)
        rest = (m.group(2) or "").strip()
        try:
            new = (self.cwd / target).resolve(strict=False)
        except (OSError, ValueError):
            # Malformed path, permission error, etc. — leave
            # cwd alone and let `sh -c` produce the error.
            return cmd, False
        if not new.is_dir():
            return cmd, False
        self.cwd = new
        return rest, True

