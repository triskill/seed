"""Host-side shell subprocess execution.

Task 1.1 added the smallest useful `exec_command`: a subprocess
with piped stdout/stderr. Task 1.2 swaps that for a PTY-backed
implementation so ANSI color codes are preserved end-to-end —
programs like `ls --color=auto` (and anything else that auto-
detects TTY, e.g. `git`, `diff`, `ls`) only emit color escapes
when they detect a terminal, and a PIPE is not a terminal.

Still bare-bones: no output truncation (Task 1.3), no
cancellation (Task 1.4), no CWD persistence (Task 1.5). Treat
those as separate concerns — they each get a focused change in
their own task.

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


# Strips CSI sequences (ESC [ ... final-byte). Good enough for
# the common cases (colors, cursor moves) — full ECMA-48 coverage
# is a future task if we ever need it.
_ANSI_CSI_RE = re.compile(r"\x1b\[[0-9;?]*[ -/]*[@-~]")


def strip_ansi(text: str) -> str:
    """Return `text` with common ANSI escape sequences removed.

    Used when the caller explicitly opts out of color codes via
    `capture_ansi=False`; clients that render to a TTY want the
    codes preserved, headless clients usually don't.
    """
    return _ANSI_CSI_RE.sub("", text)


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
    """

    stdout: str
    stderr: str
    exit_code: int
    captured_ansi: bool


async def exec_command(
    cmd: str,
    *,
    cwd: str | None = None,
    timeout: float | None = None,
    capture_ansi: bool = True,
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

    def _run_pty() -> tuple[bytes, int]:
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
            return b"".join(chunks), -1
        if os.WIFEXITED(status):
            exit_code = os.WEXITSTATUS(status)
        elif os.WIFSIGNALED(status):
            exit_code = 128 + os.WTERMSIG(status)
        else:
            exit_code = -1

        return b"".join(chunks), exit_code

    try:
        output, exit_code = await asyncio.wait_for(
            loop.run_in_executor(None, _run_pty),
            timeout=timeout,
        )
    except asyncio.TimeoutError:
        # Best-effort cleanup: SIGKILL the whole process group
        # (the child + any helpers it spawned, like the `sleep 5`
        # child of `sh -c "sleep 5"`), close the master fd so the
        # in-flight `os.read` in the executor thread unblocks,
        # and let the thread reap the child on its own. We don't
        # await the executor future here — it's still running in
        # a background thread that will clean up after itself
        # (and asyncio would warn about the result being
        # discarded, which is the desired behaviour: we already
        # have a TimeoutError to raise).
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
        raise

    stdout = output.decode("utf-8", errors="replace")
    if not capture_ansi:
        stdout = strip_ansi(stdout)

    return ExecResult(
        stdout=stdout,
        stderr="",  # PTY: stdout and stderr share the slave fd.
        exit_code=exit_code,
        captured_ansi=capture_ansi,
    )
