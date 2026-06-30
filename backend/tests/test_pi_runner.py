"""Tests for the PiRunner (Task 2.2).

PiRunner is a thin PTY-backed wrapper around the `pi` CLI. It
owns:
  * spawning pi in a PTY (so TTY-detecting programs see a TTY)
  * writing the user's message to the PTY master
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
import sys
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
            lines.append(line)
            if line == "done":
                break
    return lines


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
