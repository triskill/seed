"""Middle-man dispatch-JSON detection (Task 3.4).

The middle-man agent is a `pi` instance in conversation with
the user. When it has enough information to hand a task off
to the worker, it emits a fenced JSON block in its output:

    ```json
    {"intent": "build_feature", "feature": "...", "spec": "..."}
    ```

The orchestrator's middle-man read loop scans each line of
the agent's output for this block; when one is found, the
parsed JSON is forwarded to the worker pi so the worker can
build the feature. The same block is also still broadcast
to chat clients (the chat UI may want to display the
dispatch as a card, or the user may want to see what the
agent decided to build).

This module is a tiny pure helper — it owns the regex and
the parse step. The orchestrator owns the stateful buffer
that decides *when* to call into this module (because the
buffer has to live across multiple `read_lines()` iterations
when the block is split across several output lines).
"""
from __future__ import annotations

import json
import re
from typing import Optional

# Matches a single fenced ```json block, including the body.
# DOTALL so `.` matches newlines; non-greedy `.*?` so a
# second fenced block in the same buffer doesn't get swept
# up. The plan spec is `r'```json\n(.*?)\n```'` — kept
# verbatim for traceability.
DISPATCH_RE = re.compile(r"```json\n(.*?)\n```", re.DOTALL)


def extract_dispatch(text: str) -> Optional[dict]:
    """Return the parsed JSON from the first fenced ```json block
    in `text`, or None if no such block is present.

    Args:
        text: The accumulated middle-man output to scan. May
              be a single line or many; the regex is multiline
              so the block can span newlines.

    Returns:
        The parsed dispatch dict on success, None if no
        fenced block is present. Invalid JSON inside a
        well-formed fenced block raises `json.JSONDecodeError`
        — that is treated as a programmer / agent error
        (the prompt template would be wrong) and should not
        be silently swallowed.
    """
    m = DISPATCH_RE.search(text)
    if m is None:
        return None
    return json.loads(m.group(1))
