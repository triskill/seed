#!/usr/bin/env python3
"""Fake worker that responds to a dispatch with progress events (Task 3.5).

When the middle-man hands off a task, the orchestrator sends
the dispatch JSON to the worker's stdin. This fixture mimics
the real worker's response: read one line (the dispatch),
emit three progress events to stdout (so the orchestrator's
worker read loop has something to broadcast to chat
clients), and then a `done` marker.

Used by the Task 3.5 test to confirm that worker output
streams to the chat WebSocket tagged as `worker_line`
(so the chat UI can render worker progress distinct from
the middle-man's stream of thought).
"""
from __future__ import annotations

import json
import sys
import time


def main() -> int:
    # Read one line of input. Phase 4: the orchestrator wraps
    # the dispatch JSON in a pi RPC `prompt` command:
    # `{"type":"prompt","message":"<dispatch json>"}`. We
    # accept both the wrapped form (real orchestrator) and
    # the bare form (older tests).
    line = sys.stdin.readline()
    prompt = ""
    if line:
        try:
            cmd = json.loads(line)
            if isinstance(cmd, dict) and cmd.get("type") == "prompt":
                prompt = cmd.get("message", "")
            else:
                prompt = line.strip()
        except json.JSONDecodeError:
            prompt = line.strip()

    for i in range(3):
        event = {
            "type": "progress",
            "kind": "edit",
            "text": f"worker step {i + 1} of 3 (prompt was {prompt!r})",
        }
        sys.stdout.write(json.dumps(event) + "\n")
        sys.stdout.flush()
        time.sleep(0.1)

    # Task-done marker (Phase 4: with summary attribute).
    # The orchestrator's worker read loop detects this and
    # broadcasts `complete` + `app_reload` to chat clients
    # (Task 3.6). Phase 4 added the `summary` attribute —
    # the orchestrator surfaces it as the `summary` field
    # on the `complete` WS event, which is what the chat
    # UI shows as the "X is ready" bubble. We emit a
    # realistic summary so end-to-end tests can assert on
    # the full wire format.
    sys.stdout.write(
        '<task:done summary="Built /habits page with 3 progress steps."/>\n'
    )
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
