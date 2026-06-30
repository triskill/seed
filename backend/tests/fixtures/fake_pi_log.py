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
import sys


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--log",
        required=True,
        help="Path to write the received prompt to.",
    )
    args = parser.parse_args()

    # Read one line of input. If stdin is closed, treat as empty.
    line = sys.stdin.readline()
    prompt = line.rstrip("\n") if line else ""

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
