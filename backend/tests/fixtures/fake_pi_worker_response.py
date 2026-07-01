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
    # Read one line of input. If stdin is closed (the orchestrator
    # never sent us anything), the prompt is empty and we still
    # emit the events — useful for tests that drive the worker
    # directly without going through the dispatch path.
    line = sys.stdin.readline()
    prompt = line.strip() if line else ""

    for i in range(3):
        event = {
            "type": "progress",
            "kind": "edit",
            "text": f"worker step {i + 1} of 3 (prompt was {prompt!r})",
        }
        sys.stdout.write(json.dumps(event) + "\n")
        sys.stdout.flush()
        time.sleep(0.1)

    sys.stdout.write("done\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
