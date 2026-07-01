"""Unit tests for the worker task-done marker parser (Phase 4).

Phase 4 enriched the worker's `<task:done/>` marker to
carry an inline `summary="..."` attribute. The orchestrator
surfaces that string as the `summary` field on the
`complete` WS event — the chat UI's "X is ready" bubble.

`parse_task_done` is a tiny pure function in
`seed_backend/events.py`. These tests lock in the
contract: which inputs return which values, what happens
with malformed markers, and what comes back for the bare
form.
"""
from __future__ import annotations

import json

import pytest

from seed_backend.events import parse_task_done


def test_parse_task_done_returns_none_for_non_marker():
    """Plain text is not a task-done marker."""
    assert parse_task_done("hello world") is None
    assert parse_task_done("") is None
    assert parse_task_done("done") is None
    # A near-miss that should NOT match (wrong tag name).
    assert parse_task_done("<task:doneish/>") is None
    # Wrong tag, right shape — also should NOT match.
    assert parse_task_done("<other:done summary=\"x\"/>") is None


def test_parse_task_done_returns_empty_string_for_bare_marker():
    """`<task:done/>` (no attributes) returns the empty string.

    The orchestrator uses this to mean "marker is present,
    no summary provided" — and falls back to a placeholder
    summary for the chat UI. A return of `None` would be
    ambiguous (marker? non-marker?); the empty string is
    a third, distinct value.
    """
    assert parse_task_done("<task:done/>") == ""


def test_parse_task_done_returns_summary_attribute():
    """`<task:done summary="..."/>` returns the summary string."""
    line = '<task:done summary="Built /habits page with 3 steps."/>'
    assert parse_task_done(line) == "Built /habits page with 3 steps."


def test_parse_task_done_handles_empty_summary():
    """`<task:done summary=""/>` returns the empty string (not None)."""
    assert parse_task_done('<task:done summary=""/>') == ""


def test_parse_task_done_ignores_unknown_attributes():
    """Other attributes (none today, but a future extension) are ignored.

    The orchestrator only cares about `summary`. If the worker
    prompt is later extended to emit `<task:done files_changed="3"
    summary="..."/>`, the parser should still return the
    summary and silently drop the unknown attribute.
    """
    line = '<task:done files_changed="3" summary="Updated 3 files."/>'
    assert parse_task_done(line) == "Updated 3 files."


def test_parse_task_done_finds_marker_in_longer_line():
    """The parser is forgiving about leading/trailing content.

    Real pi output may emit the marker with a log prefix or
    trailing whitespace. The regex anchors on `<task:done`,
    not on the whole line, so it matches in either case.
    """
    assert parse_task_done("[info] <task:done summary=\"ok\"/>") == "ok"
    # Trailing whitespace / newline already stripped by the
    # PTY reader, but the parser shouldn't depend on that.
    assert parse_task_done('<task:done summary="ok"/>   ') == "ok"


def test_parse_task_done_quotes_can_contain_punctuation():
    """The summary value can contain punctuation, but not a literal `\"`.

    Real-world summaries will contain `.`, `,`, `:`, `/`, etc.
    The regex captures everything up to the next `\"`; an
    embedded quote would close the attribute early. That's
    an acceptable limitation for v0.1 — the worker prompt
    tells the agent to write a one-sentence summary without
    quotes. A future task can swap to a proper XML parser
    if richer summaries are needed.
    """
    line = '<task:done summary="OK: built /v1, /v2, and /v3 routes."/>'
    assert parse_task_done(line) == "OK: built /v1, /v2, and /v3 routes."


def test_parse_task_done_rejects_malformed_quoting():
    """A marker with unclosed quotes returns None (no match).

    The regex is strict: an unclosed `"` means the marker
    doesn't match the expected shape. The orchestrator
    treats it as a non-marker line, so it gets broadcast
    as a regular `worker_line` (visible in the chat) but
    does NOT trigger `complete` + `app_reload`. Better to
    miss a done signal than to fire one on malformed input.
    """
    assert parse_task_done('<task:done summary="unclosed') is None


# ---------------------------------------------------------------------
# translate_pi_line — Phase 4 helper that unwraps pi's JSONL events
# ---------------------------------------------------------------------

from seed_backend.events import (
    translate_pi_line,
    WS_TYPE_MIDDLEMAN_LINE,
    WS_TYPE_WORKER_LINE,
)


def test_translate_plain_text_passes_through_middleman():
    """A non-JSON line is broadcast as a `middleman_line`
    and accumulated as text (with a trailing newline for
    dispatch detection)."""
    events, text = translate_pi_line("hello world", role="middleman")
    assert events == [{"type": "middleman_line", "line": "hello world"}]
    assert text == "hello world\n"


def test_translate_plain_text_passes_through_worker():
    """The same line, with role=worker, is tagged `worker_line`."""
    events, text = translate_pi_line("hello world", role="worker")
    assert events == [{"type": "worker_line", "line": "hello world"}]
    assert text == "hello world\n"


def test_translate_text_delta_emits_delta_as_line():
    """A real `text_delta` from pi's `message_update` event
    emits the delta text as a chat line and accumulates it."""
    line = json.dumps(
        {
            "type": "message_update",
            "assistantMessageEvent": {
                "type": "text_delta",
                "delta": "Hello, ",
            },
        }
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events == [{"type": "middleman_line", "line": "Hello, "}]
    # Text chunk for accumulation: the raw delta (no
    # trailing newline for streaming deltas; the
    # dispatcher handles cross-delta stitching).
    assert text == "Hello, "


def test_translate_thinking_delta_is_hidden_by_default():
    """`thinking_delta` events are not broadcast (the chat UI
    hides the agent's chain-of-thought) and produce no
    accumulated text. The thinking text is still in the
    event for diagnostics but we don't surface it."""
    line = json.dumps(
        {
            "type": "message_update",
            "assistantMessageEvent": {
                "type": "thinking_delta",
                "delta": "let me think...",
            },
        }
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events is None
    assert text == ""


def test_translate_tool_execution_start_emits_json_line():
    """A tool call starts as a line carrying the full
    event JSON (so the chat UI can render "running
    <tool>" and the in-stream tool filter (Task 2.4)
    can block disallowed tools)."""
    line = json.dumps(
        {
            "type": "tool_execution_start",
            "toolCallId": "call_1",
            "toolName": "bash",
            "args": {"cmd": "ls"},
        }
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events is not None
    assert len(events) == 1
    assert events[0]["type"] == "middleman_line"
    # The line field is the original event JSON (so the
    # chat UI / filter can parse it the same way they
    # parse raw pi output).
    assert json.loads(events[0]["line"])["toolName"] == "bash"
    assert text == ""


def test_translate_turn_end_returns_none():
    """`turn_end` is a control signal — not broadcast,
    no accumulated text. The orchestrator handles it by
    scanning the buffer for the dispatch JSON."""
    line = json.dumps({"type": "turn_end"})
    events, text = translate_pi_line(line, role="middleman")
    assert events is None
    assert text == ""


def test_translate_response_is_silently_dropped():
    """`response` (ack for a prompt command) is not interesting
    to the chat UI; drop silently."""
    line = json.dumps(
        {"type": "response", "command": "prompt", "success": True}
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events is None
    assert text == ""


def test_translate_extension_ui_request_is_silently_dropped():
    """Extension UI requests (e.g. widgets) have no
    representation in the chat UI; drop silently. The
    extension will time out / no-op on its own."""
    line = json.dumps(
        {
            "type": "extension_ui_request",
            "id": "abc",
            "method": "setWidget",
        }
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events is None
    assert text == ""


def test_translate_unknown_json_falls_through_with_text_field():
    """An unknown JSON event (e.g. from a fake pi fixture
    that emits `{"type":"progress","text":"step 1"}`) is
    broadcast as a chat line (raw JSON, so the chat UI /
    tests can parse it) and the `text` field is accumulated."""
    line = json.dumps(
        {"type": "progress", "kind": "thought", "text": "step 1"}
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events is not None
    assert events[0]["type"] == "middleman_line"
    assert json.loads(events[0]["line"])["text"] == "step 1"
    # The accumulated text is the `text` field (with a
    # trailing newline so the dispatch regex sees line
    # boundaries). This is what makes the fake pi
    # dispatch fixture work with the new RPC wrapper.
    assert text == "step 1\n"


def test_translate_unknown_json_without_text_field_uses_raw_line():
    """If the unknown JSON has no `text` field, fall back
    to the raw line for text accumulation."""
    line = json.dumps({"type": "progress", "kind": "edit"})
    events, text = translate_pi_line(line, role="worker")
    assert events[0]["type"] == "worker_line"
    # Raw line is the fallback.
    assert text == line + "\n"


def test_translate_worker_role_tags_worker_line():
    """role=worker tags all broadcasts as `worker_line`
    (chat UI distinguishes the two agents)."""
    line = json.dumps({"type": "progress", "text": "x"})
    events, _ = translate_pi_line(line, role="worker")
    assert events[0]["type"] == "worker_line"


def test_translate_middleman_role_tags_middleman_line():
    """role=middleman tags all broadcasts as `middleman_line`."""
    line = json.dumps({"type": "progress", "text": "x"})
    events, _ = translate_pi_line(line, role="middleman")
    assert events[0]["type"] == "middleman_line"


def test_translate_defaults_to_middleman_role():
    """The default role is middleman (matches the historical
    call site before Phase 4 added the role parameter)."""
    line = "plain text"
    events, _ = translate_pi_line(line)
    assert events[0]["type"] == "middleman_line"


def test_translate_message_end_extracts_text_content():
    """`message_end` carries the full final assistant
    message. Some models (cheap / non-streaming) emit
    text only in `message_end`, never as `text_delta`
    chunks. The translator must extract the text
    content blocks so the dispatch / task-done scan
    can fire on the final text.

    Real-world example (deepseek-v4-flash via opencode-go):
    the model wrote a thinking block + a text block in
    a single message, with no streaming chunks. Without
    this case, the orchestrator's worker read loop
    never sees `<task:done .../>` in the accumulated
    text, and `complete` + `app_reload` never fire.
    """
    line = json.dumps(
        {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [
                    {
                        "type": "thinking",
                        "thinking": "Mission accomplished. Let me report done.",
                    },
                    {
                        "type": "text",
                        "text": '<task:done summary="Added /hello route."/>',
                    },
                ],
            },
        }
    )
    events, text = translate_pi_line(line, role="worker")
    assert events is not None
    # The text is broadcast (the marker line is filtered
    # by the orchestrator, not the translator — the
    # translator just extracts the text).
    assert events[0]["type"] == "worker_line"
    assert "<task:done" in events[0]["line"]
    # And accumulated so parse_task_done() can find it.
    assert '<task:done summary="Added /hello route."/>' in text


def test_translate_message_end_ignores_thinking_content():
    """The thinking block is hidden by default — only
    `text` blocks are broadcast / accumulated. This is
    consistent with the `thinking_delta` handling."""
    line = json.dumps(
        {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [
                    {"type": "thinking", "thinking": "internal notes..."},
                    {"type": "text", "text": "user-visible reply"},
                ],
            },
        }
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events is not None
    # Only the text content is broadcast.
    assert "user-visible reply" in events[0]["line"]
    assert "internal notes" not in events[0]["line"]
    assert "internal notes" not in text


def test_translate_message_end_handles_empty_content():
    """A `message_end` with no `text` blocks (pure
    thinking, or a tool-only turn) returns no events and
    no accumulated text. The chat UI should not see a
    stray empty line."""
    line = json.dumps(
        {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [{"type": "thinking", "thinking": "hmm"}],
            },
        }
    )
    events, text = translate_pi_line(line, role="worker")
    assert events is None
    assert text == ""


def test_translate_message_end_concatenates_multiple_text_blocks():
    """If for some reason the model emits multiple text
    blocks in one message, concatenate them in order so
    the dispatch regex / task-done scan sees the
    complete output."""
    line = json.dumps(
        {
            "type": "message_end",
            "message": {
                "role": "assistant",
                "content": [
                    {"type": "text", "text": "First part. "},
                    {"type": "text", "text": "Second part."},
                ],
            },
        }
    )
    events, text = translate_pi_line(line, role="middleman")
    assert events[0]["line"] == "First part. Second part."
    assert text == "First part. Second part."
