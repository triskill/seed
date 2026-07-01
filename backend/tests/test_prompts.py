"""Sanity tests for the role-specific system prompt files (Phase 4).

`pi_cmd_for_role` references `backend/prompts/middleman.md`
and `backend/prompts/worker.md` by absolute path. A typo,
an accidental `git mv` to the wrong directory, or a
zero-byte file (e.g. from a botched merge) would crash
the lifespan at spawn time with a confusing error from
pi. These tests catch the obvious breakages early.

The tests check:
  * both files exist;
  * both are non-empty;
  * each file mentions its role's key protocol
    (middleman.md: the dispatch JSON shape; worker.md:
    the `<task:done/>` marker with summary) so a
    gutted prompt can't ship.

What the tests do NOT check: prompt quality, LLM
behaviour, or that the agent follows the spec. Those
are exercised by the manual end-to-end test (Task 4.3).
"""
from __future__ import annotations

from seed_backend.orchestrator import (
    _MIDDLEMAN_PROMPT,
    _WORKER_PROMPT,
)


def test_middleman_prompt_file_exists():
    """The middleman.md file is present at the path the
    orchestrator passes to pi.

    If this test fails, either the file was moved/renamed
    (update `_MIDDLEMAN_PROMPT` in `orchestrator.py`) or
    the working tree is missing the file (run `git pull`).
    """
    assert _MIDDLEMAN_PROMPT.is_file(), (
        f"middleman prompt not found at {_MIDDLEMAN_PROMPT}"
    )


def test_worker_prompt_file_exists():
    """The worker.md file is present at the path the
    orchestrator passes to pi."""
    assert _WORKER_PROMPT.is_file(), (
        f"worker prompt not found at {_WORKER_PROMPT}"
    )


def test_middleman_prompt_is_non_trivial():
    """The middleman prompt has real content (not a stub).

    A zero-byte or one-line file would let the lifespan
    pass spawn-time checks but leave the agent with no
    instructions. The exact content threshold is fuzzy,
    so we use a low bar (>200 bytes) that catches an
    accidental wipe without being fragile to rewording.
    """
    size = _MIDDLEMAN_PROMPT.stat().st_size
    assert size > 200, (
        f"middleman prompt is suspiciously small: {size} bytes at "
        f"{_MIDDLEMAN_PROMPT}"
    )


def test_worker_prompt_is_non_trivial():
    """The worker prompt has real content (not a stub)."""
    size = _WORKER_PROMPT.stat().st_size
    assert size > 200, (
        f"worker prompt is suspiciously small: {size} bytes at "
        f"{_WORKER_PROMPT}"
    )


def test_middleman_prompt_describes_dispatch_protocol():
    """The middleman prompt tells the agent to emit the
    dispatch JSON block.

    The orchestrator scans the middle-man's output for
    ```json ... ``` blocks. If the prompt doesn't describe
    this protocol, the agent won't emit one and the worker
    will never be invoked. We assert the prompt mentions
    the JSON / dispatch keywords so a refactor that drops
    the protocol gets caught here.
    """
    text = _MIDDLEMAN_PROMPT.read_text(encoding="utf-8")
    assert "json" in text.lower(), "middleman prompt doesn't mention 'json'"
    assert "dispatch" in text.lower(), (
        "middleman prompt doesn't mention 'dispatch' — the agent "
        "won't know to emit the orchestrator-expected block"
    )


def test_worker_prompt_describes_task_done_marker():
    """The worker prompt tells the agent to emit
    `<task:done summary="..."/>`.

    The orchestrator's worker read loop watches for this
    marker to fire `complete` + `app_reload`. If the prompt
    doesn't describe the marker (or describes the bare
    `<task:done/>` without the summary attribute), the
    chat UI gets an empty/placeholder summary.
    """
    text = _WORKER_PROMPT.read_text(encoding="utf-8")
    assert "<task:done" in text, (
        "worker prompt doesn't mention the <task:done ... /> marker"
    )
    assert 'summary="' in text, (
        "worker prompt doesn't tell the agent to include summary=\"...\" "
        "in the marker — the chat UI will show the placeholder"
    )
