"""Unit tests for the middle-man dispatch-JSON detector (Task 3.4).

Pure helper tests — no PTY, no orchestrator. They lock in
the regex behavior of `extract_dispatch` so the orchestrator
loop can rely on the contract: "given a string that contains
a fenced ```json block, return the parsed dict; otherwise
None".
"""
from __future__ import annotations

import json

import pytest

from seed_backend.middleman import extract_dispatch


def test_extract_returns_none_when_no_block():
    """Plain text without any fenced block returns None."""
    assert extract_dispatch("hello there\n") is None
    assert extract_dispatch("") is None


def test_extract_returns_none_for_unfenced_brace():
    """A bare JSON object without the fence is not a dispatch.

    The middle-man prompt is allowed to mention JSON
    examples in conversation (e.g. "here's what a dispatch
    looks like: { ... }") and we must not misinterpret that
    as a real dispatch.
    """
    raw = "here's a dispatch: {\"intent\": \"build_feature\"}"
    assert extract_dispatch(raw) is None


def test_extract_parses_single_line_block():
    """A fenced block on a single line is detected."""
    raw = '```json\n{"intent": "build_feature"}\n```'
    assert extract_dispatch(raw) == {"intent": "build_feature"}


def test_extract_parses_multiline_block():
    """A fenced block that spans multiple lines is detected."""
    raw = (
        "preamble line\n"
        "```json\n"
        "{\n"
        '  "intent": "build_feature",\n'
        '  "feature": "habit_tracker",\n'
        '  "spec": "Build a habit tracker"\n'
        "}\n"
        "```\n"
        "trailing line\n"
    )
    assert extract_dispatch(raw) == {
        "intent": "build_feature",
        "feature": "habit_tracker",
        "spec": "Build a habit tracker",
    }


def test_extract_picks_first_block_when_multiple():
    """If two fenced blocks exist, the first one wins.

    Non-greedy `.*?` in the regex is what makes this work;
    the test guards against a future change to greedy
    matching that would sweep up the second block.
    """
    raw = (
        "```json\n"
        '{"first": 1}\n'
        "```\n"
        "some text in between\n"
        "```json\n"
        '{"second": 2}\n'
        "```\n"
    )
    assert extract_dispatch(raw) == {"first": 1}


def test_extract_raises_on_invalid_json_inside_block():
    """Invalid JSON inside a well-formed fence is an error.

    The agent's prompt template is what produces the dispatch
    block; a malformed block means the prompt template is
    broken, not a normal runtime condition. We let the
    exception propagate so a developer notices.
    """
    raw = "```json\n{not valid json}\n```"
    with pytest.raises(json.JSONDecodeError):
        extract_dispatch(raw)
