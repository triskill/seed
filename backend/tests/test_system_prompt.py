"""Tests for the system-prompt preload (Task 2.5).

The runner accepts a system prompt (a multi-line
string) and writes it to the child's stdin as soon
as the child starts, before any user message. The
prompt is followed by a blank line so the child can
distinguish it from the user's first message (which
the orchestrator sends next).

This is the orchestrator's way of telling the middle-
man / worker what their job is, in plain text. The
prompt lives in `prompts/middleman.md` /
`prompts/worker.md` in the runtime directory; for
tests, an inline string is enough.

The fake pi is extended (a sibling fixture for this
test) so a test can verify "did the runner write
the prompt to my stdin?".
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest

from seed_backend.pi_runner import PiRunner


# A fake pi that reads stdin and echoes it back on
# stdout, line by line, prefixed with 'echo: '. Lets
# a test assert exactly what the runner wrote to the
# child's stdin. Lives next to the other fake-pi
# fixtures so test discovery picks it up cleanly.
_ECHO_FIXTURE = Path(__file__).parent / "fixtures" / "fake_pi_echo.py"


def _echo_cmd() -> list[str]:
    return [sys.executable, str(_ECHO_FIXTURE)]


async def _read_lines_with_timeout(
    runner: PiRunner, stop_after: str | None = None, max_lines: int = 50
) -> list[str]:
    """Read lines until the runner signals EOF, max_lines, or we see `stop_after`.

    The echo fake pi reads stdin until EOF; in our
    tests we send a "hi" message after the prompt
    preload and break as soon as the echoed "hi"
    comes back (`stop_after="echo: hi"`), so the
    test doesn't depend on stop() closing the master
    fd in a particular order.
    """
    lines: list[str] = []
    async with asyncio.timeout(5):
        async for line in runner.read_lines():
            if line is None:
                break
            lines.append(line)
            if stop_after is not None and stop_after in line:
                break
            if len(lines) >= max_lines:
                break
    return lines


def test_runner_sends_system_prompt_on_start():
    """The runner writes the system prompt to the child's stdin on start().

    A runner constructed with system_prompt='You are a
    helpful assistant' must, after start() resolves,
    have already written that string (plus a blank
    line) to the child's stdin. The fake pi reads
    stdin and echoes it back on stdout, so we can
    inspect what the runner wrote by reading lines.
    """
    prompt = "You are a helpful assistant."

    async def scenario():
        runner = PiRunner(
            cmd=_echo_cmd(),
            role="worker",
            system_prompt=prompt,
        )
        try:
            await runner.start()
            # The fake pi echoes its stdin. The runner
            # already wrote the prompt + a blank line.
            # We then send a follow-up message so the
            # fake pi sees EOF when stop() closes the
            # master fd. (The fake pi is just
            # `for line in stdin`, so it exits when
            # stdin closes.)
            await runner.send("hi")
            lines = await _read_lines_with_timeout(
                runner, stop_after="echo: hi"
            )
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # The fake pi reads its stdin line by line and
    # echoes each line prefixed with "echo:". The
    # runner wrote the prompt and then a blank line.
    # So the echoed output should contain the prompt
    # text (one line) and a blank-echo.
    prompt_lines = [
        ln[len("echo: "):] for ln in lines if ln.startswith("echo: ")
    ]
    assert prompt in prompt_lines, (
        f"expected system prompt in echoed output, got: {prompt_lines!r}"
    )
    # And there should be a blank line after it (the
    # runner's delimiter between system prompt and
    # the user's first message).
    assert "" in prompt_lines, (
        f"expected a blank line after system prompt, got: {prompt_lines!r}"
    )


def test_system_prompt_defaults_to_empty_string():
    """The system_prompt arg defaults to '' (no preload)."""
    runner = PiRunner(cmd=_echo_cmd(), role="worker")
    assert runner.system_prompt == ""


def test_runner_sends_no_prompt_when_empty():
    """With system_prompt='', start() doesn't write anything to stdin.

    The runner's preload is a no-op when the prompt
    is the empty string. The fake pi just sits waiting
    for stdin; we don't need to assert anything on
    its output here beyond "nothing got preloaded".
    """
    async def scenario():
        runner = PiRunner(cmd=_echo_cmd(), role="worker", system_prompt="")
        try:
            await runner.start()
            # No prompt was written, so we shouldn't
            # see any echo output for a fraction of a
            # second. Send a message and verify the
            # runner still works.
            await runner.send("hi")
            lines = await _read_lines_with_timeout(
                runner, stop_after="echo: hi"
            )
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # We sent "hi" so we should see "echo: hi" in the
    # output (no "echo: " lines for an empty prompt).
    assert "echo: hi" in lines


def test_runner_accepts_multiline_system_prompt():
    """A multi-line prompt is written verbatim (with the trailing blank line)."""
    prompt = "Line one.\nLine two.\nLine three."

    async def scenario():
        runner = PiRunner(
            cmd=_echo_cmd(),
            role="worker",
            system_prompt=prompt,
        )
        try:
            await runner.start()
            await runner.send("go")
            lines = await _read_lines_with_timeout(
                runner, stop_after="echo: go"
            )
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    prompt_lines = [
        ln[len("echo: "):] for ln in lines if ln.startswith("echo: ")
    ]
    # Each line of the multi-line prompt should be
    # echoed back, plus a final blank.
    assert "Line one." in prompt_lines
    assert "Line two." in prompt_lines
    assert "Line three." in prompt_lines
    assert "" in prompt_lines
