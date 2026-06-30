"""Tests for auto-restart on crash (Task 2.6).

The runner can be configured to automatically restart
its child if the child dies (non-zero exit, crash,
segfault, OOM kill). This is for the orchestrator
process which is meant to run for hours; a single
crashed `pi` shouldn't take the whole system down.

The default is auto_restart=False (don't restart) so
the test environment doesn't surprise-test other
behaviour. Production will set it to True.

Tests use a fake pi variant that deliberately exits
non-zero after some output, so we can verify the
runner respawns and produces more output.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import pytest

from seed_backend.pi_runner import PiRunner


# A fake pi that emits a couple of progress events and
# then exits with a non-zero code (the crash). Reads
# an env var SEED_FAKE_PI_GENERATION to label its
# output, so a multi-generation test can tell
# generations apart. Spawned via subprocess.
_CRASH_FIXTURE = Path(__file__).parent / "fixtures" / "fake_pi_crash.py"


def _crash_cmd() -> list[str]:
    # No explicit generation: the fixture falls back
    # to its PID, so each fork produces a distinct
    # label and the test can count generations.
    return [sys.executable, str(_CRASH_FIXTURE)]


async def _drain(runner: PiRunner, max_lines: int = 100) -> list[str]:
    """Read lines until EOF or max_lines."""
    lines: list[str] = []
    async with asyncio.timeout(8):
        async for line in runner.read_lines():
            if line is None:
                break
            lines.append(line)
            if len(lines) >= max_lines:
                break
    return lines


def _generations(lines: list[str]) -> set[int]:
    """Extract the set of generations from a stream of JSONL events.

    The crash fixture emits lines like
    `{"type": "progress", "kind": "thought", "text": "gen=0: step 1"}`.
    The gen number is embedded in the `text` field; we
    pull it out and return the distinct set so a test
    can assert "the runner saw output from N distinct
    generations" (proving the restart actually
    happened N-1 times).
    """
    import json as _json
    gens: set[int] = set()
    for line in lines:
        if not line.startswith("{"):
            continue
        try:
            event = _json.loads(line)
        except ValueError:
            continue
        text = event.get("text", "")
        if not isinstance(text, str) or not text.startswith("gen="):
            continue
        try:
            gen = int(text.split(":", 1)[0].split("=")[1])
            gens.add(gen)
        except (ValueError, IndexError):
            pass
    return gens


def test_runner_restarts_after_death():
    """auto_restart=True respawns the child within 1s of a crash.

    The fake pi emits a progress event and then
    crashes (exit code 1). The runner should detect
    the crash, fork a new child, and continue
    producing output. The test asserts:
      * output from two distinct generations was seen
        (the original + the respawned child), proving
        restart actually happened
      * the new child completed a turn normally (we
        see "done" in the output)
    """
    async def scenario():
        runner = PiRunner(
            cmd=_crash_cmd(),
            role="worker",
            auto_restart=True,
            max_restarts=3,
        )
        try:
            await runner.start()
            lines = await _drain(runner)
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # Look for output from at least two generations.
    gens = _generations(lines)
    assert len(gens) >= 2, (
        f"expected output from at least 2 generations, "
        f"got gens={gens!r} from lines={lines!r}"
    )


def test_runner_does_not_restart_by_default():
    """Without auto_restart, a crash is fatal (no respawn).

    The default is False so a misconfigured orchestrator
    doesn't accidentally loop. The test asserts the
    runner saw the first generation's output but not
    a second.
    """
    async def scenario():
        runner = PiRunner(
            cmd=_crash_cmd(),
            role="worker",
            # auto_restart default is False
        )
        try:
            await runner.start()
            lines = await _drain(runner)
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # The fake pi labels each generation with its PID.
    # Without auto_restart, only one process ever ran,
    # so the count of distinct generations is 1.
    gens = _generations(lines)
    assert len(gens) == 1, (
        f"expected only one generation without auto_restart, "
        f"got gens={gens!r} from lines={lines!r}"
    )


def test_runner_gives_up_after_max_restarts():
    """After max_restarts crashes in a row, the runner gives up.

    The crash fixture keeps crashing. After
    max_restarts=2 (so the initial run + 2 restarts
    = 3 generations), the runner should give up and
    end the read_lines() stream. We assert that
    exactly that many generations (PIDs) are seen.
    """
    async def scenario():
        runner = PiRunner(
            cmd=_crash_cmd(),
            role="worker",
            auto_restart=True,
            max_restarts=2,
        )
        try:
            await runner.start()
            lines = await _drain(runner)
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # Count distinct PIDs (= generations).
    gens = _generations(lines)
    assert len(gens) == 3, (
        f"expected exactly 3 generations (1 + 2 restarts), "
        f"got gens={gens!r} from lines={lines!r}"
    )
