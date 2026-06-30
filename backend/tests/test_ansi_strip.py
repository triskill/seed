"""Tests for ANSI escape stripping in the pi runner.

Task 2.3: pi (and many tools it shells out to) emits
ANSI escape sequences for colors, cursor moves, etc. We
want the JSONL events on the wire to be free of these
so the chat UI can render them as plain text. The
runner gets a `strip_ansi=True` flag in __init__ that
defaults to True for production but can be turned off
for debugging (so the route layer can see the raw
output if something looks off).
"""
from __future__ import annotations

import asyncio
import re
import sys
from pathlib import Path

import pytest

from seed_backend.pi_runner import PiRunner


# Path to the fake pi fixture. Re-derived per-test so
# pytest's chdir behaviour doesn't matter.
def fake_pi_cmd() -> list[str]:
    return [sys.executable, str(Path(__file__).parent / "fixtures" / "fake_pi.py")]


# Fake pi variant that writes some ANSI escapes on
# stdout. Spawned via subprocess so we can feed a
# different fixture for these tests.
_ANSI_FIXTURE = Path(__file__).parent / "fixtures" / "fake_pi_ansi.py"


def _ansi_cmd() -> list[str]:
    return [sys.executable, str(_ANSI_FIXTURE)]


def test_ansi_strip_removes_csi_color_codes():
    """A runner configured with strip_ansi=True yields lines free of ESC[ sequences.

    The fake pi writes 'red text' wrapped in a red
    CSI sequence. With stripping on, the consumer sees
    the literal text without the escape codes. This is
    the common case for the chat UI.
    """
    async def scenario():
        runner = PiRunner(cmd=_ansi_cmd(), role="worker", strip_ansi=True)
        try:
            await runner.start()
            await runner.send("hi")
            lines: list[str] = []
            async with asyncio.timeout(5):
                async for line in runner.read_lines():
                    if line is None:
                        break
                    lines.append(line)
                    if len(lines) >= 2:  # 'red text' + 'done'
                        break
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # The ANSI-bearing 'red text' line should arrive stripped.
    assert "red text" in lines[0]
    # No ESC byte (0x1b) anywhere in the output.
    assert all("\x1b" not in ln for ln in lines), (
        f"Expected no ESC bytes after stripping, got: {lines!r}"
    )


def test_ansi_strip_disabled_preserves_raw_sequences():
    """strip_ansi=False passes the bytes through verbatim (debug mode)."""
    async def scenario():
        runner = PiRunner(cmd=_ansi_cmd(), role="worker", strip_ansi=False)
        try:
            await runner.start()
            await runner.send("hi")
            lines: list[str] = []
            async with asyncio.timeout(5):
                async for line in runner.read_lines():
                    if line is None:
                        break
                    lines.append(line)
                    if len(lines) >= 2:
                        break
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())

    # The 'red text' line should still contain the ESC byte.
    assert "\x1b" in lines[0], (
        f"Expected ESC bytes preserved when strip_ansi=False, got: {lines!r}"
    )


def test_ansi_strip_default_is_on():
    """The default value of strip_ansi is True (production-friendly)."""
    runner = PiRunner(cmd=fake_pi_cmd(), role="worker")
    assert runner.strip_ansi is True
