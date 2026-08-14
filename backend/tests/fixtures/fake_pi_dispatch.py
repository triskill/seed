#!/usr/bin/env python3
"""Fake middle-man that emits a dispatch JSON block (Task 3.4).

Speaks the same minimal protocol as the other fake pi fixtures
(it doesn't emit JSONL progress events; it just writes a
fenced ```json block) so the orchestrator's dispatch-JSON
detector can be tested without an LLM.

Wire format (stdout):
    Some preamble line that the orchestrator will broadcast
    as a middleman_line (so we can confirm preamble lines
    aren't mistaken for a dispatch).
    ```json
    {"intent": "build_feature", "feature": "...", "spec": "..."}
    ```
    done

The `done` marker is plain text (same convention as
`fake_pi.py`); the orchestrator treats it as terminal output
in Task 3.3 and as turn boundary in 3.4.

Reads one line of stdin (the user prompt) but does not echo
it back — the test is about whether the dispatch makes it to
the worker, not about user-prompt fidelity.
"""
from __future__ import annotations

import json
import sys


def main() -> int:
    # Read one line of input. Phase 4: orchestrator wraps in
    # a pi RPC `prompt` command. We just need to consume the
    # line so the output pipe flushes promptly; the prompt contents are
    # irrelevant for this fixture (it always emits the same
    # canned dispatch).
    sys.stdin.readline()

    # A preamble line — this should be broadcast as a normal
    # middleman_line and must NOT be mistaken for a dispatch
    # (the regex requires the ```json opener on the same line
    # as the closing ```, so plain text is safe).
    sys.stdout.write("thinking about the user's request\n")
    sys.stdout.flush()

    # The dispatch block. Uses a stable, recognisable spec
    # string so the test can grep for it in the worker's log.
    spec = "Build a habit tracker with a daily check-in form"
    dispatch = {
        "intent": "build_feature",
        "feature": "habit_tracker",
        "spec": spec,
    }
    sys.stdout.write("```json\n")
    sys.stdout.write(json.dumps(dispatch) + "\n")
    sys.stdout.write("```\n")
    sys.stdout.flush()

    # Turn-boundary marker (plain text, like fake_pi.py).
    sys.stdout.write("done\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
