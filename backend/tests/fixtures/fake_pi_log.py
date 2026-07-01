#!/usr/bin/env python3
"""Fake pi that logs the received prompt to a file (Task 3.2).

Unlike the other fake pi fixtures, this one doesn't speak the
JSONL event protocol. It reads one line of stdin, writes the
prompt to a log file (path given as `--log`), and exits. The
test that drives the WebSocket /chat endpoint checks the log
file to verify the user message reached the middle-man — a
simpler, file-based check than reaching into the runner's
internal queue from a different event loop.

The `--log` path is also where the test confirms the *worker*
got the dispatch (Task 3.4 will use the same mechanism).
"""
from __future__ import annotations

import argparse
import json
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--log",
        required=True,
        help="Path to write the received prompt to.",
    )
    args = parser.parse_args()

    # Read one line of input. Phase 4: the orchestrator wraps
    # the user text in a pi RPC `prompt` command
    # (`{"type":"prompt","message":"<text>"}`). We accept both
    # the wrapped form (real orchestrator) and the bare form
    # (older tests, manual usage). The log file is what the
    # tests assert on, so we write the *message* — the user
    # text or dispatch JSON, not the RPC wrapper.
    line = sys.stdin.readline()
    prompt = ""
    if line:
        try:
            cmd = json.loads(line)
            if isinstance(cmd, dict) and cmd.get("type") == "prompt":
                prompt = cmd.get("message", "")
            else:
                prompt = line.rstrip("\n")
        except json.JSONDecodeError:
            prompt = line.rstrip("\n")

    # Write to the log file atomically: write to a temp path in
    # the same directory, then rename. Avoids the test seeing a
    # half-written file if it polls before we finish.
    target = args.log
    tmp = target + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        f.write(prompt + "\n")
    # atomic-ish rename
    import os
    os.replace(tmp, target)
    return 0


if __name__ == "__main__":
    sys.exit(main())
