"""Tests for the fake pi fixture.

Task 2.1: a small script that pretends to be the `pi` CLI for
runner-level tests. It reads user input from stdin, writes
JSONL "progress" events to stdout, and exits after a few
messages. The same fixture will be reused by every later
runner test (2.2-2.6) to drive the runner in tests.

The fixture is implemented as a Python module so the test
imports the function and runs it in-process with a real
subprocess. That keeps the test independent of where the
fixture file lives on disk.
"""
from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path

import pytest


# Path to the fixture module. Re-derived per-test so the test
# works from any CWD (pytest may chdir while collecting).
FIXTURE_PATH = Path(__file__).parent / "fixtures" / "fake_pi.py"


def test_fake_pi_writes_jsonl_progress_and_exits():
    """The fake emits 3 progress lines then 'done' as a 4th.

    Sends 'hi' on stdin, expects 4 newline-terminated lines:
    3 progress events and a final 'done' marker. The 'done'
    marker is what the runner uses to know the turn is over.
    """
    result = subprocess.run(
        [sys.executable, str(FIXTURE_PATH)],
        input="hi\n",
        capture_output=True,
        text=True,
        timeout=5,
    )
    lines = [ln for ln in result.stdout.split("\n") if ln]
    assert len(lines) == 4, f"expected 4 lines, got {lines!r}"
    # First three are progress events.
    for line in lines[:3]:
        event = json.loads(line)
        assert event["type"] == "progress"
        assert event["kind"] == "thought"
        assert "text" in event
    # Fourth is the done marker (plain text, not JSON).
    assert lines[3] == "done"


def test_fake_pi_can_be_run_standalone():
    """`echo hi | python fake_pi.py` from a shell works (smoke test)."""
    result = subprocess.run(
        f"echo hi | {sys.executable} {FIXTURE_PATH}",
        shell=True,
        capture_output=True,
        text=True,
        timeout=5,
    )
    # 3 progress lines + 'done'.
    assert "done" in result.stdout
    assert result.returncode == 0


def test_fake_pi_module_imports():
    """The fixture file is importable as a Python module.

    The runner (later tasks) needs the fixture path on disk to
    spawn it, but the test suite also wants to import its helpers
    (e.g. the `main` function) directly. Importing should not
    execute the script — it should only define the functions.
    """
    import importlib.util

    spec = importlib.util.spec_from_file_location("fake_pi", FIXTURE_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    # The fixture must expose a `main` function so we can call it
    # programmatically if needed.
    assert hasattr(module, "main")
    assert callable(module.main)
