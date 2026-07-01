"""Wire-level event types and control markers (Task 3.6, 4.x).

Centralises the constants the orchestrator and chat layer
agree on: task-done markers emitted by the worker pi, the
event `type` strings the chat WS client receives, etc.

Why a separate module? Two reasons:
    1. The chat forwarder (in `chat.py`) and the orchestrator
       both build WS event dicts; keeping the `type` strings
       here means a typo in one place can't desync the wire
       format silently.
    2. The worker prompt template (Phase 4) will reference
       the task-done marker as a string the agent is told to
       emit. Having one source of truth avoids the prompt and
       the detector drifting apart.
"""
from __future__ import annotations

import json
import re
from typing import Optional


# Worker control marker. The worker agent is told (in its
# system prompt, Phase 4) to write a line containing this
# tag when it has finished a turn. Two shapes are accepted:
#
#   <task:done/>                     (no summary — v0.1 placeholder)
#   <task:done summary="..."/>       (summary inline, preferred for
#                                     Phase 4; the orchestrator
#                                     surfaces it as the
#                                     `summary` field on the
#                                     `complete` WS event)
#
# Chosen as an XML-ish tag because:
#   * it's unambiguous in a stream of text/code (the angle
#     brackets and slash are rare in natural language);
#   * it stands out in logs and is greppable for debugging;
#   * LLMs reliably emit short, exact strings when prompted.
TASK_DONE_MARKER = "<task:done/>"

# Matches a `<task:done .../>` tag and captures an optional
# `summary="..."` attribute. Used by the worker read loop
# (in `orchestrator.py`) to extract the summary without
# pulling in a full XML parser. Group 1 is the summary
# (without surrounding quotes), or None if the marker
# doesn't carry one.
#
# Pattern notes:
#   * `<task:done` literal anchor; the agent is told to
#     emit exactly that prefix.
#   * `(?P<attrs>(?:\s+[a-zA-Z_][\w-]*="[^"]*")*)\s*/>` —
#     zero or more `name="value"` attributes, then `/>`.
#     We intentionally don't support unquoted attrs, single
#     quotes, or self-closing-with-content (`<.../>` only);
#     the worker prompt is the spec.
#   * DOTALL not needed (the value is on one line).
TASK_DONE_RE = re.compile(
    r'<task:done(?P<attrs>(?:\s+[a-zA-Z_][\w-]*="[^"]*")*)\s*/>'
)

# Captures `summary="..."` (or any other attribute's value)
# from the attrs string. Used after `TASK_DONE_RE` matches.
_SUMMARY_ATTR_RE = re.compile(r'summary="([^"]*)"')


def parse_task_done(line: str) -> Optional[str]:
    """If `line` is a `<task:done ... />` marker, return the
    embedded `summary` attribute (may be empty string). If
    `line` is not the marker, return None.

    The orchestrator's worker read loop calls this for
    every line; the None return means "not a task-done
    marker, broadcast as a regular `worker_line`".

    Args:
        line: A single line of worker stdout (newline
              already stripped by the PTY reader).

    Returns:
        The summary string if the marker carries one,
        the empty string if the marker is bare
        (`<task:done/>`), or None if the line is not
        a task-done marker at all.
    """
    m = TASK_DONE_RE.search(line)
    if m is None:
        return None
    attrs = m.group("attrs")
    sm = _SUMMARY_ATTR_RE.search(attrs)
    if sm is None:
        # Bare `<task:done/>` — valid marker, no summary.
        return ""
    return sm.group(1)


# WebSocket event `type` values, sent server -> client.
# Kept in one place so a chat client maintainer can find
# the full set without grepping the backend.
WS_TYPE_MIDDLEMAN_LINE = "middleman_line"
WS_TYPE_WORKER_LINE = "worker_line"
WS_TYPE_COMPLETE = "complete"
WS_TYPE_APP_RELOAD = "app_reload"
WS_TYPE_ERROR = "error"

# Pi RPC command shape (Phase 4). The orchestrator wraps
# every user message / dispatch payload in this JSON
# object and writes it to the `pi` subprocess's stdin
# (which is running in `--mode rpc`). Pi expects one
# command per line:
#
#     {"type": "prompt", "message": "<text>"}
#
# The `id` field is optional and used for request/response
# correlation. We don't bother with it in v0.1 — the
# orchestrator sends one prompt at a time and reads the
# response events off the same stdout.
PI_CMD_TYPE_PROMPT = "prompt"

# pi --mode rpc event types the orchestrator handles.
# Anything not in this set is logged + ignored (most are
# lifecycle events: agent_start, turn_start, etc., which
# the orchestrator doesn't need to surface to the chat
# UI).
PI_EVENT_TEXT_DELTA = "text_delta"
PI_EVENT_THOUGHT_DELTA = "thinking_delta"
PI_EVENT_TOOL_START = "tool_execution_start"
PI_EVENT_TOOL_END = "tool_execution_end"
PI_EVENT_TURN_END = "turn_end"
PI_EVENT_MESSAGE_END = "message_end"
PI_EVENT_MESSAGE_END = "message_end"
PI_EVENT_RESPONSE = "response"
PI_EVENT_EXTENSION_UI_REQUEST = "extension_ui_request"


def translate_pi_line(
    raw_line: str,
    role: str = "middleman",
) -> tuple[Optional[list[dict]], str]:
    """Translate one line of `pi` output to chat events.

    pi in `--mode rpc` emits JSONL events on stdout:
    `message_update` (with nested `assistantMessageEvent`
    carrying `text_delta` or `thinking_delta` chunks),
    `tool_execution_start` / `tool_execution_end` (with
    `toolName` and `args` / `result`), `turn_end` (turn
    boundary), `response` (ack for a command), and
    lifecycle events. The orchestrator's chat UI wants
    plain text deltas, not raw JSON; this helper
    unwraps the events and returns:

      * a list of chat-line dicts (one per user-visible
        chunk) ready to broadcast, or `None` if the
        line is a control signal the orchestrator
        should handle internally (e.g. `turn_end` →
        "this turn is done, scan for dispatch");
      * the plain text to accumulate for the running
        buffer the orchestrator uses to scan for
        control markers (dispatch JSON, `<task:done/>`).

    Plain text input (the fake pi fixtures' output, or
    any non-JSON line from real pi) is treated as a
    single chat line and returned in full. This keeps
    the test fixtures working without an RPC wrapper:
    the fake worker writes `{"type":"progress",
    "kind":"edit","text":"step 1 of 3"}` per line; we
    pass each line through unchanged and the existing
    `test_middle_man_progress_streams_to_chat_ws`
    assertion (`json.loads(fr["line"])["type"] == ...`)
    still holds.

    Args:
        raw_line: One line of pi's stdout (newline
                  already stripped by the PTY reader).
        role:     "middleman" or "worker". Determines
                  which WS event type tag the
                  broadcasts use (`middleman_line` vs
                  `worker_line`); the chat UI renders
                  the two agents distinctly.

    Returns:
        A `(events, text)` tuple:
          * `events` is a list of dicts with `type` and
            `line` / `tool` / etc. fields — one per
            chat-streamable chunk, or `None` if the
            line is a control signal.
          * `text` is the chunk of plain text to
            accumulate for the dispatch / task-done
            scan. Empty string if the line produced
            no user-visible text.
    """
    if role == "worker":
        ws_type = WS_TYPE_WORKER_LINE
    else:
        ws_type = WS_TYPE_MIDDLEMAN_LINE

    # Try to parse as a real pi event. If it doesn't
    # parse, or the parsed object isn't a dict, treat
    # the line as plain text (fake fixtures + safe
    # fallback).
    event: Optional[dict] = None
    try:
        parsed = json.loads(raw_line)
        if isinstance(parsed, dict):
            event = parsed
    except (ValueError, TypeError):
        pass

    if event is None:
        # Plain text — broadcast verbatim and accumulate
        # with a trailing newline so the dispatch regex
        # (which expects ```json\n...\n```) sees line
        # boundaries when text is line-buffered (the
        # fake pi fixtures write one line at a time).
        return (
            [{"type": ws_type, "line": raw_line}],
            raw_line + "\n",
        )

    t = event.get("type")
    # ---- Lifecycle / control events ----------------------------
    if t == PI_EVENT_TURN_END:
        # Turn boundary: the orchestrator scans the
        # accumulated text for the dispatch JSON / task-done
        # marker. We return None so the caller doesn't
        # broadcast this as a chat event.
        return (None, "")

    if t == PI_EVENT_RESPONSE:
        # Ack for a `prompt` command. Not interesting
        # to the chat UI; ignore.
        return (None, "")

    if t == PI_EVENT_EXTENSION_UI_REQUEST:
        # Extension is asking for UI state (e.g. a
        # widget to render). The chat UI has no
        # concept of widgets. Ignore — the extension
        # will time out or no-op.
        return (None, "")

    # ---- Message content deltas ---------------------------------
    if t == "message_update":
        ame = event.get("assistantMessageEvent", {})
        ame_type = ame.get("type")
        if ame_type == PI_EVENT_TEXT_DELTA:
            delta = ame.get("delta", "")
            return (
                [{"type": ws_type, "line": delta}],
                delta,
            )
        if ame_type == PI_EVENT_THOUGHT_DELTA:
            # Hide the agent's chain-of-thought by
            # default — the chat UI has a "thinking"
            # card that summarises it. We accumulate
            # the text for diagnostics but don't
            # broadcast.
            return (None, "")
        # Other assistantMessageEvent types
        # (text_start, text_end, thinking_start, ...)
        # are lifecycle — ignore.
        return (None, "")

    if t == PI_EVENT_MESSAGE_END:
        # End of an assistant turn. The full message
        # content is in `message.content` as a list of
        # blocks: `{"type": "thinking", "thinking":
        # "..."}` and `{"type": "text", "text": "..."}`.
        #
        # Some models (notably the cheaper ones) don't
        # stream `text_delta` chunks and only emit the
        # final text in `message_end`. We have to
        # support both, so extract the text content
        # here and treat it as if it were a text_delta
        # — broadcast as a chat line, accumulate for
        # the dispatch / task-done scan. We do NOT
        # broadcast thinking content (it's hidden).
        #
        # We broadcast the text as a single line (the
        # full final text) even if deltas were also
        # streamed — the chat UI's de-duplication is
        # out of scope here, and double-displaying the
        # last word is a known acceptable glitch for
        # non-streaming models. The thinking-vs-text
        # split is the only important filter.
        msg = event.get("message", {})
        content = msg.get("content", [])
        text_chunks = []
        for block in content:
            if not isinstance(block, dict):
                continue
            block_type = block.get("type")
            if block_type == "text":
                t_text = block.get("text", "")
                if isinstance(t_text, str):
                    text_chunks.append(t_text)
        full_text = "".join(text_chunks)
        if full_text:
            return (
                [{"type": ws_type, "line": full_text}],
                full_text,
            )
        return (None, "")

    # ---- Tool call lifecycle -----------------------------------
    if t == PI_EVENT_TOOL_START:
        # Surface as a line so the chat UI can show
        # "reading app.py..." and the in-stream tool
        # filter (Task 2.4) can block disallowed
        # tools. The filter still works because the
        # JSON it expects is exactly what pi emits.
        return (
            [{"type": ws_type, "line": json.dumps(event)}],
            "",
        )
    if t == PI_EVENT_TOOL_END:
        # Same — broadcast as a line so the UI can
        # show the result.
        return (
            [{"type": ws_type, "line": json.dumps(event)}],
            "",
        )

    # ---- Anything else (agent_start, turn_start, fake
    # fixtures, future pi versions, extensions) ---------
    # Broadcast the raw line so the chat UI / tests can
    # see it. The fake pi fixtures use this path: they
    # emit `{"type":"progress","kind":"thought","text":"..."}`
    # JSON events that look like real pi events but with
    # a different schema. The existing
    # `test_middle_man_progress_streams_to_chat_ws` test
    # does `json.loads(fr["line"])` on each frame, so we
    # pass the raw JSON through.
    #
    # For text accumulation (dispatch / task-done scan),
    # prefer the event's `text` field if it has one
    # (the fake fixtures use this for stream-of-thought
    # text); fall back to the raw line. We append "\n"
    # so the dispatch regex's line-oriented ` ```json\n
    # ...\n``` ` pattern sees boundaries.
    text_field = event.get("text")
    text_chunk = text_field if isinstance(text_field, str) else raw_line
    return (
        [{"type": ws_type, "line": raw_line}],
        text_chunk + "\n",
    )
