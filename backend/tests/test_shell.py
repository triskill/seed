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
    """A failing command surfaces stderr and a non-zero exit code."""
    async def scenario():
        return await exec_command("echo nope 1>&2; exit 7")

    result = asyncio.run(scenario())

    assert result.stdout == ""
    assert result.stderr == "nope\n"
    assert result.exit_code == 7


def test_shell_exec_route_returns_command_output():
    """POST /shell/exec with {"command": "echo hi"} returns stdout/exit_code."""
    with TestClient(app) as client:
        response = client.post("/shell/exec", json={"command": "echo hi"})

    assert response.status_code == 200
    body = response.json()
    assert body == {"stdout": "hi\n", "stderr": "", "exit_code": 0}


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
