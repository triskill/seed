"""Tests for the tool-call filter (Task 2.4).

The middle-man pi is supposed to be read-only: it can
inspect the user's web app but never edit files or
run shell commands. The worker has no such restriction.
This module tests the runtime filter that enforces the
read-only constraint as defense in depth (the system
prompt + pi's `--tools` flag at spawn time are the
primary defenses; this filter is a backstop in case
pi changes its tool set or someone misconfigures the
flags).

The filter watches each line read from the child for
a `tool_execution_start` event and checks `toolName`
against the role's read-only whitelist. If a
disallowed tool is requested, the filter aborts the
run by sending SIGTERM to the child and surfacing
`ToolCallBlocked` to the consumer of read_lines().
"""
from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

import pytest

from seed_backend.pi_runner import PiRunner, ToolCallBlocked


def fake_pi_cmd() -> list[str]:
    return [sys.executable, str(Path(__file__).parent / "fixtures" / "fake_pi.py")]


# Fake pi variant that emits a tool_execution_start
# event for a specific tool. Used to drive the filter
# tests; the test passes the tool name via stdin.
_TOOL_FIXTURE = Path(__file__).parent / "fixtures" / "fake_pi_tool.py"


def _tool_cmd(tool_name: str) -> list[str]:
    return [
        sys.executable,
        str(_TOOL_FIXTURE),
        tool_name,
    ]


async def _collect_until_exception(
    runner: PiRunner, max_lines: int = 50
) -> list[str]:
    """Read lines from the runner until it raises, EOF, or max_lines reached."""
    lines: list[str] = []
    async with asyncio.timeout(5):
        async for line in runner.read_lines():
            if line is None:
                break
            lines.append(line)
            if len(lines) >= max_lines:
                break
    return lines


def test_filter_blocks_write_tools_for_middle_man():
    """A middleman runner rejects a 'bash' tool call from the child.

    The fake pi (driven by the second arg) emits a
    `tool_execution_start` event for `bash`. The runner,
    configured with read_only_tools={read, grep, find,
    ls} (the middle-man set), must abort the run and
    surface ToolCallBlocked to the consumer. The
    blocked event itself is NOT yielded (it would
    mislead the chat UI into thinking the call
    succeeded).
    """
    async def scenario():
        runner = PiRunner(
            cmd=_tool_cmd("bash"),
            role="middleman",
            read_only_tools={"read", "grep", "find", "ls"},
        )
        try:
            await runner.start()
            await runner.send("do a thing")
            # The first line from the fake pi is a plain
            # "starting" marker; then the tool event. The
            # filter raises before yielding the tool line.
            with pytest.raises(ToolCallBlocked) as exc_info:
                await _collect_until_exception(runner)
            return exc_info.value
        finally:
            await runner.stop()

    exc = asyncio.run(scenario())
    assert exc.tool_name == "bash"
    assert exc.event is not None
    assert exc.event["type"] == "tool_execution_start"
    assert exc.event["toolName"] == "bash"


def test_filter_passes_readonly_tool_calls():
    """A 'read' tool call flows through the filter untouched.

    The middle-man is allowed to call read; the
    tool_execution_start event is yielded to the
    consumer as a normal line. (Interpretation of
    the event is the route layer's job, not the
    runner's.)
    """
    async def scenario():
        runner = PiRunner(
            cmd=_tool_cmd("read"),
            role="middleman",
            read_only_tools={"read", "grep", "find", "ls"},
        )
        try:
            await runner.start()
            await runner.send("inspect")
            lines = await _collect_until_exception(runner)
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # The fake emits a plain text "ok" + a tool event +
    # "done". The tool event should arrive intact.
    tool_lines = [ln for ln in lines if ln.startswith("{")]
    assert tool_lines, f"expected a tool event in output, got {lines!r}"
    event = json.loads(tool_lines[0])
    assert event["type"] == "tool_execution_start"
    assert event["toolName"] == "read"


def test_filter_is_a_noop_when_read_only_tools_is_none():
    """A worker (no read-only constraint) lets all tool calls through.

    The runner is configured with read_only_tools=None
    (the default) so it must NOT block even a
    clearly-write tool like 'bash'. This is the
    worker's default behaviour.
    """
    async def scenario():
        runner = PiRunner(
            cmd=_tool_cmd("bash"),
            role="worker",
            read_only_tools=None,  # explicit
        )
        try:
            await runner.start()
            await runner.send("do a thing")
            lines = await _collect_until_exception(runner)
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # No exception was raised; the tool event was
    # yielded to the consumer like any other line.
    tool_lines = [ln for ln in lines if ln.startswith("{")]
    assert tool_lines, f"expected a tool event in output, got {lines!r}"
    event = json.loads(tool_lines[0])
    assert event["toolName"] == "bash"


def test_filter_default_is_none():
    """read_only_tools defaults to None (no filter) for backward compat."""
    runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
    assert runner.read_only_tools is None

def test_filter_blocks_unterminated_final_tool_event():
    """EOF processing must filter a JSON event even without a final LF."""
    fixture = Path(__file__).parent / "fixtures" / "fake_pi_tool_no_newline.py"

    async def scenario():
        runner = PiRunner(
            cmd=[sys.executable, str(fixture)],
            role="middleman",
            read_only_tools={"read", "grep", "find", "ls"},
        )
        try:
            await runner.start()
            with pytest.raises(ToolCallBlocked) as exc_info:
                await _collect_until_exception(runner)
            return exc_info.value
        finally:
            await runner.stop()

    violation = asyncio.run(scenario())
    assert violation.tool_name == "bash"
