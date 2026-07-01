"""Wire-level event types and control markers (Task 3.6).

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


# Worker control marker. The worker agent is told (in its
# system prompt, Phase 4) to write a line containing exactly
# this string when it has finished a turn. The orchestrator's
# worker read loop scans for it; on a match it broadcasts
# `complete` + `app_reload` to all chat clients and then
# stops reading more worker output for this turn.
#
# Chosen as an XML-ish tag because:
#   * it's unambiguous in a stream of text/code (the angle
#     brackets and slash are rare in natural language);
#   * it stands out in logs and is greppable for debugging;
#   * LLMs reliably emit short, exact strings when prompted.
TASK_DONE_MARKER = "<task:done/>"


# WebSocket event `type` values, sent server -> client.
# Kept in one place so a chat client maintainer can find
# the full set without grepping the backend.
WS_TYPE_MIDDLEMAN_LINE = "middleman_line"
WS_TYPE_WORKER_LINE = "worker_line"
WS_TYPE_COMPLETE = "complete"
WS_TYPE_APP_RELOAD = "app_reload"
WS_TYPE_ERROR = "error"
