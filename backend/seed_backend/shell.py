"""Host-side shell subprocess execution.

Task 1.1: the smallest useful `exec_command` — runs a shell command
via `asyncio.create_subprocess_exec`, captures stdout and stderr, and
returns the results. The Android "Shell" screen hits
`POST /shell/exec` (in `service.py`) which calls this function.

This is deliberately the bare-bones version: no PTY (Task 1.2 will
add one to capture ANSI color codes), no output truncation
(Task 1.3), no cancellation (Task 1.4), no CWD persistence
(Task 1.5). Treat those as separate concerns — they each get a
focused change in their own task.

Security note: this orchestrator is intended to run on a trusted LAN
with the Android client as the only caller; the endpoint is
unauthenticated by design for v0.1.
"""
from __future__ import annotations

import asyncio
from dataclasses import dataclass


@dataclass
class ExecResult:
    """Result of running a shell command.

    Attributes:
        stdout:    Captured standard output, decoded as UTF-8
                   (with replacement for any undecodable bytes so a
                   single bad byte doesn't blow up the request).
        stderr:    Captured standard error, decoded the same way.
        exit_code: Process exit status. 0 means success; any other
                   value (1-255) is the exit code the command returned.
    """

    stdout: str
    stderr: str
    exit_code: int


async def exec_command(
    cmd: str,
    *,
    cwd: str | None = None,
    timeout: float | None = None,
) -> ExecResult:
    """Run `cmd` via `sh -c` and return the captured output.

    The command is passed to `sh -c`, so shell features (pipes, `&&`,
    glob expansion, etc.) work. stdout and stderr are captured to
    pipes. On timeout, the subprocess is killed and TimeoutError is
    re-raised.

    Args:
        cmd:     The shell command to run.
        cwd:     Optional working directory for the subprocess.
        timeout: Optional wall-clock timeout in seconds. If the
                 command runs longer than this, the subprocess is
                 killed and `asyncio.TimeoutError` is raised.

    Returns:
        ExecResult with decoded stdout, stderr, and exit code.
    """
    process = await asyncio.create_subprocess_exec(
        "sh",
        "-c",
        cmd,
        cwd=cwd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )

    try:
        stdout_bytes, stderr_bytes = await asyncio.wait_for(
            process.communicate(),
            timeout=timeout,
        )
    except asyncio.TimeoutError:
        # Best-effort cleanup: kill the subprocess and wait for it
        # to actually die before propagating the timeout. The pipes
        # are closed by communicate() / the kill below.
        try:
            process.kill()
        except ProcessLookupError:
            pass
        await process.wait()
        raise

    # communicate() guarantees returncode is set.
    assert process.returncode is not None
    return ExecResult(
        stdout=stdout_bytes.decode("utf-8", errors="replace"),
        stderr=stderr_bytes.decode("utf-8", errors="replace"),
        exit_code=process.returncode,
    )
