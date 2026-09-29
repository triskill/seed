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

from pathlib import Path

from seed_backend.service import app
from seed_backend.shell import ExecCancelled, ExecResult, ShellSession, exec_command


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


def test_shell_route_persists_cwd_after_compound_command():
    with TestClient(app) as client:
        first = client.post('/shell/exec', json={'command': 'cd /tmp && pwd'})
        second = client.post('/shell/exec', json={'command': 'pwd'})
    assert first.status_code == second.status_code == 200
    assert first.json()['stdout'] == second.json()['stdout'] == '/tmp\n'


def test_exec_in_pty_handles_color_codes():
    """The embedded runtime uses subprocess.Popen (no PTY) so ANSI is NOT preserved.

    The full PTY-backed executor was removed in the embedded-runtime
    work (Phase 8 carry-over): proot does not implement `fork(2)`,
    so the os.fork() + pty.openpty() impl raises ENOSYS. The
    executor now falls back to `subprocess.Popen` with PIPEs, which
    is portable across proot but means the child sees stdout as a
    pipe (not a TTY), so `[ -t 1 ]` is false and ANSI escapes are
    not emitted. This test pins that intentional behaviour:

      * with PIPE: command prints `not_a_tty`, no ANSI.
      * the legacy PTY behaviour is documented in the design doc
        and can be re-added once we move to a non-proot runtime
        (or use a host-side fork in a pre-fork worker process).

    The command below is the same one the original PTY test used;
    it makes the TTY detection explicit.
    """
    # `if [ -t 1 ]; then printf ESC[31mred ESC[0m; else echo not_a_tty; fi`
    cmd = (
        "if [ -t 1 ]; then "
        "printf '\\033[31mred\\033[0m\\n'; "
        "else echo not_a_tty; "
        "fi"
    )

    async def scenario():
        return await exec_command(cmd)

    result = asyncio.run(scenario())

    # Subprocess.Popen gives the child a pipe, not a TTY, so
    # `[ -t 1 ]` is false and the command takes the else branch.
    assert "not_a_tty" in result.stdout, (
        f"Expected the no-TTY branch to run, got: {result.stdout!r}"
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


def test_exec_can_be_cancelled():
    """Setting the cancel event mid-run raises `ExecCancelled` and kills the child.

    Task 1.4: `exec_command` accepts a `cancel: asyncio.Event` so
    long-running commands can be aborted by the caller (e.g. when
    a client disconnects). The implementation must (a) send
    SIGTERM to the child process group, (b) close the PTY master
    fd to unblock the in-flight read, and (c) raise `ExecCancelled`
    to the awaiting coroutine — all of which we exercise here by
    cancelling a `sleep 60` after 100ms.
    """
    async def scenario():
        cancel = asyncio.Event()

        async def trigger():
            # Sleep 100ms on the same loop `asyncio.run` is
            # driving, then set the event so the cancel branch
            # in `exec_command` fires.
            await asyncio.sleep(0.1)
            cancel.set()

        asyncio.create_task(trigger())
        return await exec_command("sleep 60", cancel=cancel)

    with pytest.raises(ExecCancelled):
        asyncio.run(scenario())


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


def test_cwd_persists_across_calls():
    """`cd` in one `ShellSession.exec` call is visible in the next.

    Task 1.5: `ShellSession` keeps a per-session `cwd` that the
    PTY-backed executor is launched from. Because v0.1 uses a
    heuristic (not a real persistent shell process), we update
    `self.cwd` ourselves when the command starts with `cd <path>`.
    Here, after `cd /tmp` the next `pwd` should report `/tmp`.
    """
    async def scenario():
        session = ShellSession()
        await session.exec("cd /tmp")
        return await session.exec("pwd")

    result = asyncio.run(scenario())

    assert result.stdout.strip() == str(Path("/tmp").resolve()).rstrip()
    # On Linux the resolved path is still /tmp; on macOS it'd be
    # /private/tmp. Both forms end with "tmp", which is the
    # portable check — the command literally is `cd /tmp`.
    assert result.stdout.strip().endswith("tmp")


def test_session_persists_final_cwd_of_compound_expression(tmp_path):
    async def scenario():
        session = ShellSession(cwd=tmp_path)
        first = await session.exec('cd /tmp && pwd')
        second = await session.exec('pwd')
        return session, first, second

    session, first, second = asyncio.run(scenario())
    assert first.exit_code == 0
    assert first.stdout == second.stdout == '/tmp\n'
    assert session.cwd == Path('/tmp')


def test_session_cd_tilde_and_previous_directory(tmp_path, monkeypatch):
    home = tmp_path / 'home'
    home.mkdir()
    monkeypatch.setenv('HOME', str(home))

    async def scenario():
        session = ShellSession(cwd=tmp_path)
        home_result = await session.exec('cd ~; pwd')
        previous = await session.exec('cd -')
        return session, home_result, previous

    session, home_result, previous = asyncio.run(scenario())
    assert home_result.stdout == f'{home}\n'
    assert previous.stdout == f'{tmp_path}\n'
    assert session.cwd == tmp_path


def test_session_quoted_directory_and_failed_cd(tmp_path):
    target = tmp_path / 'with spaces'
    target.mkdir()

    async def scenario():
        session = ShellSession(cwd=tmp_path)
        quoted = await session.exec('cd "with spaces" && pwd')
        failed = await session.exec('cd missing && echo should-not-run')
        current = await session.exec('pwd')
        return quoted, failed, current

    quoted, failed, current = asyncio.run(scenario())
    assert quoted.stdout == f'{target}\n'
    assert failed.exit_code != 0
    assert 'should-not-run' not in failed.stdout
    assert current.stdout == f'{target}\n'


def test_session_serializes_concurrent_calls(tmp_path):
    target = tmp_path / 'later'
    target.mkdir()

    async def scenario():
        session = ShellSession(cwd=tmp_path)
        first = asyncio.create_task(session.exec('sleep 0.1; cd later'))
        await asyncio.sleep(0.02)
        second = asyncio.create_task(session.exec('pwd'))
        return await first, await second, session.cwd

    first, second, cwd = asyncio.run(scenario())
    assert first.exit_code == 0
    assert second.stdout == f'{target}\n'
    assert cwd == target


def test_session_preserves_output_status_and_ansi(tmp_path):
    async def scenario():
        session = ShellSession(cwd=tmp_path)
        raw = await session.exec("printf '\\033[31mout\\033[0m\\n'; echo err >&2; cd /tmp; false")
        stripped = await session.exec("printf '\\033[31mplain\\033[0m\\n'", capture_ansi=False)
        return raw, stripped, session.cwd

    raw, stripped, cwd = asyncio.run(scenario())
    assert raw.stdout == '\x1b[31mout\x1b[0m\nerr\n'
    assert raw.stderr == ''
    assert raw.exit_code == 1
    assert raw.captured_ansi is True
    assert stripped.stdout == 'plain\n'
    assert stripped.captured_ansi is False
    assert cwd == Path('/tmp')


def test_session_timeout_and_cancel_do_not_commit_partial_cwd(tmp_path):
    async def scenario():
        session = ShellSession(cwd=tmp_path)
        with pytest.raises(asyncio.TimeoutError):
            await session.exec('cd /tmp; sleep 5', timeout=0.05)
        assert (await session.exec('pwd')).stdout == f'{tmp_path}\n'
        cancel = asyncio.Event()
        task = asyncio.create_task(session.exec('cd /tmp; sleep 5', cancel=cancel))
        await asyncio.sleep(0.05)
        cancel.set()
        with pytest.raises(ExecCancelled):
            await task
        assert (await session.exec('pwd')).stdout == f'{tmp_path}\n'

    asyncio.run(scenario())


def test_pre_cancelled_command_never_launches(tmp_path):
    marker = tmp_path / 'launched'

    async def scenario():
        session = ShellSession(cwd=tmp_path)
        cancelled = asyncio.Event()
        cancelled.set()
        with pytest.raises(ExecCancelled):
            await asyncio.wait_for(session.exec(f'touch {marker}', cancel=cancelled), 1)
        return await session.exec('pwd')

    assert asyncio.run(scenario()).stdout == f'{tmp_path}\n'
    assert not marker.exists()


def test_cancellation_during_delayed_pid_publication_kills_child(tmp_path, monkeypatch):
    import seed_backend.shell as shell

    real_popen = shell.subprocess.Popen
    spawned = asyncio.Event()
    loop = None

    def delayed_popen(*args, **kwargs):
        proc = real_popen(*args, **kwargs)
        loop.call_soon_threadsafe(spawned.set)
        import time
        time.sleep(0.15)  # cancellation happens after launch, before PID publication
        return proc

    monkeypatch.setattr(shell.subprocess, 'Popen', delayed_popen)
    marker = tmp_path / 'finished'

    async def scenario():
        nonlocal loop
        loop = asyncio.get_running_loop()
        session = ShellSession(cwd=tmp_path)
        cancel = asyncio.Event()
        task = asyncio.create_task(session.exec(f'sleep 0.5; touch {marker}', cancel=cancel))
        await spawned.wait()
        cancel.set()
        with pytest.raises(ExecCancelled):
            await asyncio.wait_for(task, 1.5)
        return await asyncio.wait_for(session.exec('pwd'), 1)

    assert asyncio.run(scenario()).stdout == f'{tmp_path}\n'
    assert not marker.exists()


def test_pwd_function_cannot_poison_session_cwd(tmp_path):
    async def scenario():
        session = ShellSession(cwd=tmp_path)
        await session.exec('pwd() { printf "/does-not-exist\\n"; }; cd /tmp')
        return session.cwd, await session.exec('pwd')

    cwd, result = asyncio.run(scenario())
    assert cwd == Path('/tmp')
    assert result.stdout == '/tmp\n'


def test_set_positional_parameters_cannot_redirect_cwd_report(tmp_path):
    target = tmp_path / 'destination'
    target.mkdir()

    async def scenario():
        session = ShellSession(cwd=tmp_path)
        result = await session.exec('printf "%s|%s\\n" "$#" "$0"; set -- bogus path; cd destination')
        return result, session.cwd, await session.exec('pwd')

    result, cwd, next_result = asyncio.run(scenario())
    assert result.stdout == '0|sh\n'
    assert cwd == target
    assert next_result.stdout == f'{target}\n'
    assert not (tmp_path / 'path').exists()


def test_session_timeout_includes_wait_for_lock(tmp_path):
    async def scenario():
        session = ShellSession(cwd=tmp_path)
        first = asyncio.create_task(session.exec('sleep 0.35; cd /tmp'))
        await asyncio.sleep(0.05)
        start = asyncio.get_running_loop().time()
        with pytest.raises(asyncio.TimeoutError):
            await session.exec('touch should-not-run', timeout=0.05)
        elapsed = asyncio.get_running_loop().time() - start
        await first
        return elapsed, session.cwd

    elapsed, cwd = asyncio.run(scenario())
    assert elapsed < 0.25
    assert cwd == Path('/tmp')
    assert not (tmp_path / 'should-not-run').exists()


def test_session_task_cancellation_releases_lock_and_keeps_cwd(tmp_path):
    async def scenario():
        session = ShellSession(cwd=tmp_path)
        task = asyncio.create_task(session.exec('cd /tmp; sleep 5'))
        await asyncio.sleep(0.05)
        task.cancel()
        with pytest.raises(asyncio.CancelledError):
            await task
        return await session.exec('pwd')

    assert asyncio.run(scenario()).stdout == f'{tmp_path}\n'


def test_shell_requires_runtime_capability_when_runtime_sets_one(monkeypatch):
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-secret")
    with TestClient(app, client=("127.0.0.1", 1234)) as client:
        denied = client.post("/shell/exec", json={"command": "echo nope"})
        allowed = client.post(
            "/shell/exec",
            json={"command": "printf %s \"$SEED_RUNTIME_CAPABILITY\""},
            headers={"Authorization": "Bearer capability-secret"},
        )
    assert denied.status_code == 401
    assert allowed.status_code == 200
    # Direct inheritance is scrubbed. A caller with the capability is already
    # trusted to invoke shell; this test protects against accidental leakage to
    # ordinary command environments.
    assert allowed.json()["stdout"] == ""
