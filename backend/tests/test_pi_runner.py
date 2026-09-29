"""Tests for the PiRunner (Task 2.2).

PiRunner is a pipe-backed wrapper around the `pi --mode rpc` CLI. It
owns:
  * spawning pi through the Android-compatible `subprocess.Popen` path
  * writing the user's message to the child's stdin pipe
  * reading pi's output line-by-line (async generator)
  * a clean shutdown path (SIGTERM, then SIGKILL after grace)

The runner does NOT (yet) do ANSI stripping, tool filtering, or
auto-restart — those are Tasks 2.3, 2.4, and 2.6. This file only
covers the lifecycle and read loop.

Tests use the fake pi from `tests/fixtures/fake_pi.py` (Task 2.1)
as the child process. That way the runner can be exercised
without an LLM or a real pi install.
"""
from __future__ import annotations

import asyncio
import subprocess
import sys
import threading
from pathlib import Path

import pytest

from seed_backend.pi_runner import PiRunner, PiRunnerNotRunning


# Resolved path to the fake pi fixture. Re-derived per-test so
# pytest's chdir behaviour doesn't matter.
def fake_pi_cmd() -> list[str]:
    return [sys.executable, str(Path(__file__).parent / "fixtures" / "fake_pi.py")]


async def _drive_to_done(runner: PiRunner) -> list[str]:
    """Read lines from the runner until the fake pi emits 'done'.

    Returns the list of lines received (excluding 'done' itself).
    Uses a hard 5s ceiling so a stuck test fails fast.
    """
    lines: list[str] = []
    async with asyncio.timeout(5):
        async for line in runner.read_lines():
            if line is None:
                # EOF — child closed the slave.
                break
            lines.append(line)
            if line == "done":
                break
    return lines


def test_cancelled_start_during_popen_reaps_spawned_child(monkeypatch):
    """Cancellation before the executor returns must not lose ownership of its child."""
    import seed_backend.pi_runner as pi_runner_module

    spawned = threading.Event()
    release = threading.Event()
    children = []
    real_popen = subprocess.Popen

    def delayed_popen(*args, **kwargs):
        process = real_popen(*args, **kwargs)
        children.append(process)
        spawned.set()
        if not release.wait(5):
            raise AssertionError('test did not release Popen')
        return process

    monkeypatch.setattr(pi_runner_module.subprocess, 'Popen', delayed_popen)

    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role='worker')
        startup = asyncio.create_task(runner.start())
        try:
            assert await asyncio.to_thread(spawned.wait, 5)
            startup.cancel()
            await asyncio.sleep(0)
            assert not startup.done()  # cancellation waits for ownership of Popen
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(startup, 5)
            await asyncio.wait_for(runner.stop(), 5)
            assert runner.pid is None
            assert children[0].returncode is not None  # Popen.wait() reaped it
        finally:
            release.set()
            if not startup.done():
                startup.cancel()
            for process in children:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)

    asyncio.run(scenario())


def test_stop_during_pending_spawn_reaps_child(monkeypatch):
    """Stop must retain ownership of a Popen still running in the executor."""
    import seed_backend.pi_runner as pi_runner_module

    entered = threading.Event()
    release = threading.Event()
    children = []
    real_popen = subprocess.Popen

    def delayed_popen(*args, **kwargs):
        process = real_popen(*args, **kwargs)
        children.append(process)
        entered.set()
        assert release.wait(5), "test did not release Popen"
        return process

    monkeypatch.setattr(pi_runner_module.subprocess, "Popen", delayed_popen)

    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        startup = asyncio.create_task(runner.start())
        try:
            assert await asyncio.to_thread(entered.wait, 5)
            shutdown = asyncio.create_task(runner.stop())
            # The marker is queued after stop's initial task step.
            marker = asyncio.Event()
            asyncio.get_running_loop().call_soon(marker.set)
            await asyncio.wait_for(marker.wait(), 5)
            assert not shutdown.done()  # cleanup must wait for the pending spawn
            release.set()
            await asyncio.wait_for(shutdown, 5)
            with pytest.raises(PiRunnerNotRunning):
                await asyncio.wait_for(startup, 5)
            assert runner.pid is None
            assert children[0].returncode is not None
        finally:
            release.set()
            if not startup.done():
                startup.cancel()
            for process in children:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)

    asyncio.run(scenario())


def test_repeated_cancellation_during_pending_spawn_reaps_child(monkeypatch):
    """A second cancellation cannot interrupt the one owner awaiting Popen."""
    import seed_backend.pi_runner as pi_runner_module

    entered = threading.Event()
    release = threading.Event()
    children = []
    real_popen = subprocess.Popen

    def delayed_popen(*args, **kwargs):
        process = real_popen(*args, **kwargs)
        children.append(process)
        entered.set()
        assert release.wait(5), "test did not release Popen"
        return process

    monkeypatch.setattr(pi_runner_module.subprocess, "Popen", delayed_popen)

    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        startup = asyncio.create_task(runner.start())
        try:
            assert await asyncio.to_thread(entered.wait, 5)
            startup.cancel()
            # The marker is queued after cancellation's task wakeup.
            marker = asyncio.Event()
            asyncio.get_running_loop().call_soon(marker.set)
            await asyncio.wait_for(marker.wait(), 5)
            startup.cancel()
            release.set()
            with pytest.raises(asyncio.CancelledError):
                await asyncio.wait_for(startup, 5)
            await asyncio.wait_for(runner.stop(), 5)
            assert children[0].returncode is not None
            assert runner.pid is None
        finally:
            release.set()
            if not startup.done():
                startup.cancel()
            for process in children:
                if process.poll() is None:
                    process.kill()
                process.wait(timeout=5)

    asyncio.run(scenario())


def test_cancelled_stop_mid_reap_still_finishes_cleanup():
    """A cancelled stop must not discard the process before it is reaped."""
    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        await runner.start()
        process = runner._process
        assert process is not None
        entered = asyncio.Event()
        release = asyncio.Event()
        original_wait = runner._wait

        async def gated_wait(child, timeout=None):
            entered.set()
            await release.wait()
            return await original_wait(child, timeout)

        runner._wait = gated_wait
        first = asyncio.create_task(runner.stop())
        try:
            await asyncio.wait_for(entered.wait(), 5)
            first.cancel()
            with pytest.raises(asyncio.CancelledError):
                await first
            second = asyncio.create_task(runner.stop())
            release.set()
            await asyncio.wait_for(second, 5)
            assert process.returncode is not None
            assert runner._executor_shutdown
        finally:
            release.set()
            if process.poll() is None:
                process.kill()
            process.wait(timeout=5)

    asyncio.run(scenario())


def test_runner_spawns_process_and_reads_stdout():
    """start() spawns the child; send() + read_lines() round-trips a message.

    The fake pi echoes the user prompt in its first progress
    event, so a successful round-trip proves both the write path
    and the read path work end-to-end.
    """
    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        try:
            await runner.start()
            await runner.send("hello pi")
            lines = await _drive_to_done(runner)
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # 3 progress lines + the 'done' marker (4 total).
    assert len(lines) == 4, f"expected 4 lines, got {lines!r}"
    # The fake pi embeds the user prompt in the first event's text.
    assert "'hello pi'" in lines[0]
    assert lines[-1] == "done"


def test_runner_start_twice_is_a_noop():
    """Calling start() on an already-running runner is safe.

    The second start() must be a no-op (same pid, no exception,
    no new process). The runner is stopped in a finally block
    so we don't leak a child subprocess + reader/waiter task
    to subsequent tests.
    """
    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        try:
            await runner.start()
            first_pid = runner.pid
            await runner.start()  # must not raise, must not re-spawn
            second_pid = runner.pid
            return first_pid, second_pid
        finally:
            await runner.stop()

    first_pid, second_pid = asyncio.run(scenario())

    assert first_pid == second_pid
    assert first_pid is not None and first_pid > 0


def test_runner_send_before_start_raises():
    """send() before start() fails with a clear error.

    Defensive: the orchestrator (later tasks) should always
    start() the runner first, but a programming mistake on the
    caller side shouldn't crash the process with a confusing
    OSError. The runner raises PiRunnerNotRunning instead.
    """
    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        await runner.send("hi")

    with pytest.raises(PiRunnerNotRunning):
        asyncio.run(scenario())


def test_runner_stop_is_idempotent():
    """stop() may be called multiple times without error.

    The orchestrator's lifespan will call stop() in its cleanup
    path, and the route layer's exception handlers may also call
    stop() defensively. Both should be safe.
    """
    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        await runner.start()
        await runner.stop()
        await runner.stop()  # second call must not raise
        await runner.stop()  # third call must not raise
        return runner

    runner = asyncio.run(scenario())
    assert runner.pid is None


def test_runner_role_is_stored():
    """The `role` argument is accessible on the instance.

    The tool-call filter (Task 2.4) will use `self.role` to decide
    which tools to allow. Storing it on the instance is the
    smallest thing that test needs; the filter logic itself
    lands in 2.4.
    """
    runner = PiRunner(cmd=fake_pi_cmd(), role="middleman")
    assert runner.role == "middleman"


def test_runner_env_is_stored_when_provided():
    """The optional `env` arg is stored on the instance.

    The runner passes it to `subprocess.Popen` instead of
    inheriting implicitly. This test only checks the
    attribute (no actual spawn — the exec path is
    covered by the e2e tests that spawn real `pi`).
    """
    env = {"PI_CODING_AGENT_DIR": "/tmp/nonexistent-for-test"}
    runner = PiRunner(cmd=fake_pi_cmd(), role="worker", env=env)
    assert runner.env == env


def test_runner_env_defaults_to_none():
    """No env means `None` (inherits parent env via `execvp`).

    Tests + the pre-Phase-3 code path that don't pass an
    env should keep working unchanged.
    """
    runner = PiRunner(cmd=fake_pi_cmd(), role="middleman")
    assert runner.env is None

def test_runner_does_not_call_python_os_fork(monkeypatch):
    """The normal round trip must work when Python-level fork is forbidden."""
    import os

    def forbidden_fork():
        raise AssertionError("PiRunner must not call os.fork()")

    monkeypatch.setattr(os, "fork", forbidden_fork)

    async def scenario():
        runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
        try:
            await runner.start()
            await runner.send("hello without fork")
            return await _drive_to_done(runner)
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())
    assert lines[-1] == "done"
    assert "'hello without fork'" in lines[0]


def test_missing_command_fails_synchronously_and_stop_cleans_executor():
    """Popen reports exec failure from start() without publishing a stale PID."""
    async def scenario():
        runner = PiRunner(
            cmd=["/definitely/missing/seed-pi"],
            role="worker",
        )
        with pytest.raises(FileNotFoundError):
            await runner.start()
        assert runner.pid is None
        await runner.stop()
        await runner.stop()
        return runner

    runner = asyncio.run(scenario())
    assert runner._executor_shutdown is True

def test_runner_preserves_utf8_split_across_pipe_reads():
    fixture = Path(__file__).parent / "fixtures" / "fake_pi_utf8_split.py"

    async def scenario():
        runner = PiRunner(cmd=[sys.executable, str(fixture)], role="worker")
        try:
            await runner.start()
            async with asyncio.timeout(5):
                return [line async for line in runner.read_lines()]
        finally:
            await runner.stop()

    assert asyncio.run(scenario()) == ["🙂"]

def test_stop_kills_term_ignoring_process_group():
    """SIGKILL escalation reaches helpers, not only the pi leader."""
    fixture = Path(__file__).parent / "fixtures" / "fake_pi_process_tree.py"

    async def scenario():
        runner = PiRunner(cmd=[sys.executable, str(fixture)], role="worker")
        await runner.start()
        async with asyncio.timeout(5):
            helper_line = await anext(runner.read_lines())
        helper_pid = int(helper_line.removeprefix("helper-pid:"))
        await runner.stop()
        return runner, helper_pid

    runner, helper_pid = asyncio.run(scenario())
    assert runner.pid is None

    # An orphan can remain briefly as a zombie until Android/Linux init reaps
    # it; either absence or zombie state proves it is no longer executing.
    stat_path = Path(f"/proc/{helper_pid}/stat")
    if stat_path.exists():
        state = stat_path.read_text().split()[2]
        assert state == "Z"


def test_rpc_request_correlates_response_and_hides_transport_frames():
    fixture = Path(__file__).parent / "fixtures" / "fake_pi_rpc.py"

    async def scenario():
        runner = PiRunner(cmd=[sys.executable, str(fixture)], role="control")
        try:
            await runner.start()
            response = await runner.rpc_request({"type": "get_available_models"})
            # The response is consumed by rpc_request, not read_lines().
            async with asyncio.timeout(1):
                await asyncio.sleep(0.05)
            assert runner._lines.empty()
            return response
        finally:
            await runner.stop()

    response = asyncio.run(scenario())
    assert response["success"] is True
    assert response["data"]["models"][0]["id"] == "gpt-test"


def test_rpc_request_timeout_removes_pending_request():
    fixture = Path(__file__).parent / "fixtures" / "fake_pi_rpc.py"

    async def scenario():
        runner = PiRunner(cmd=[sys.executable, str(fixture)], role="control")
        try:
            await runner.start()
            with pytest.raises(asyncio.TimeoutError):
                await runner.rpc_request({"type": "delay"}, timeout=0.05)
            return dict(runner._rpc_pending)
        finally:
            await runner.stop()

    assert asyncio.run(scenario()) == {}
