"""Tests for the shell subprocess module.

Task 1.1: validates `exec_command` runs a real subprocess via
`asyncio.create_subprocess_exec`, captures stdout/stderr, and returns
an `ExecResult` with the expected fields. Also smoke-tests the
`POST /shell/exec` route through the FastAPI TestClient.
"""
import asyncio
import subprocess

import pytest
from fastapi.testclient import TestClient

from seed_backend.service import app
from seed_backend.shell import ExecResult, exec_command


def test_exec_runs_echo_hi():
    """`echo hi` produces stdout='hi\\n' and exit_code=0 via the real subprocess."""
    async def scenario():
        result = await exec_command("echo hi")
        return result

    result = asyncio.run(scenario())

    assert isinstance(result, ExecResult)
    assert result.stdout == "hi\n"
    assert result.exit_code == 0


def test_exec_captures_stderr_and_nonzero_exit():
    """A failing command surfaces its merged output and a non-zero exit code.

    Task 1.2 switched the executor from piped subprocesses to a PTY,
    which means stdout and stderr share the slave fd and are
    returned interleaved in `ExecResult.stdout` (with `stderr`
    always empty). The exit code is unaffected.
    """
    async def scenario():
        return await exec_command("echo nope 1>&2; exit 7")

    result = asyncio.run(scenario())

    assert result.stdout == "nope\n"
    assert result.stderr == ""
    assert result.exit_code == 7


def test_shell_exec_route_returns_command_output():
    """POST /shell/exec with {"command": "echo hi"} returns stdout/exit_code."""
    with TestClient(app) as client:
        response = client.post("/shell/exec", json={"command": "echo hi"})

    assert response.status_code == 200
    body = response.json()
    assert body == {
        "stdout": "hi\n",
        "stderr": "",
        "exit_code": 0,
        "truncated": False,
    }


def test_exec_in_pty_handles_color_codes():
    """PTY-backed exec preserves ANSI color codes that the PIPE version stripped.

    Programs that auto-detect TTY (e.g. `ls --color=auto`) emit
    color escapes only when stdout is a terminal — a PIPE isn't
    one, so the previous subprocess-based executor saw plain text
    even from tools that would have colored their output otherwise.
    Task 1.2's PTY-backed executor makes the slave fd a real TTY,
    so the escape sequences survive end-to-end.

    The command below makes the TTY detection explicit: with PIPE
    `[ -t 1 ]` is false and printf is never called (so no ANSI);
    with a PTY `[ -t 1 ]` is true and printf emits ESC[31m...ESC[0m.
    """
    # `if [ -t 1 ]; then printf ESC[31mred ESC[0m; else echo not_a_tty; fi`
    # Octal \033 (not \x1b) is used because dash's printf (the usual
    # /bin/sh on Debian/Ubuntu) only expands octal escapes — \x1b
    # would arrive at the child as a literal "\x1b" and never reach
    # the master fd as an ESC byte.
    cmd = (
        "if [ -t 1 ]; then "
        "printf '\\033[31mred\\033[0m\\n'; "
        "else echo not_a_tty; "
        "fi"
    )

    async def scenario():
        return await exec_command(cmd)

    result = asyncio.run(scenario())

    assert "\x1b[" in result.stdout, (
        f"Expected an ANSI escape sequence (ESC[) in stdout, "
        f"got: {result.stdout!r}"
    )
    assert result.exit_code == 0


def test_exec_raises_timeout_error_and_kills_child():
    """A command exceeding the timeout is killed and TimeoutError is raised.

    The timeout branch in `exec_command` must (a) raise
    `asyncio.TimeoutError` to the caller and (b) actually kill and
    reap the child subprocess so it doesn't linger as a zombie.
    We verify (a) via pytest.raises, and (b) by checking the system
    has no `sleep 5` process matching our marker (the `[s]` bracket
    trick keeps `pgrep` from matching its own command line).
    """
    async def scenario():
        return await exec_command("sleep 5", timeout=0.1)

    with pytest.raises(asyncio.TimeoutError):
        asyncio.run(scenario())

    # The kill + wait in the timeout branch should have reaped the
    # child before the exception propagated. Give the OS a hair to
    # settle, then assert no matching process is alive.
    result = subprocess.run(
        ["pgrep", "-f", "[s]leep 5"],
        capture_output=True,
        text=True,
    )
    assert result.returncode == 1, (
        f"Expected no 'sleep 5' process to remain, "
        f"but pgrep found PIDs: {result.stdout!r}"
    )
    assert result.stdout.strip() == ""


def test_exec_truncates_huge_output():
    """Commands producing more than MAX_LINES lines are truncated.

    Task 1.3 caps captured output at MAX_LINES=5000 / MAX_BYTES=1MB.
    Once either limit is hit, the executor stops accumulating and
    flags `result.truncated=True` so the caller knows the output
    is incomplete. `seq 1 10000` produces 10000 newline-terminated
    lines — well over the cap — so the result should be truncated
    and contain at most 5000 lines.
    """
    async def scenario():
        return await exec_command("seq 1 10000")

    result = asyncio.run(scenario())

    assert result.truncated is True
    assert len(result.stdout.splitlines()) <= 5000
