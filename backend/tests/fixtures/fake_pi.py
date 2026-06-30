#!/usr/bin/env python3
"""Fake `pi` CLI for runner tests (Task 2.1).

Pretends to be the real `pi-coding-agent` Node.js CLI by
speaking the same JSONL-on-stdio protocol. Reads one line
of user input from stdin, then writes three "progress"
events to stdout (sleeping 100ms between each so the runner
gets a chance to interleave reads) and a final "done" line
to mark the turn boundary. Exits 0 on success.

Used by `PiRunner` tests (Tasks 2.2-2.6) so the runner can
be exercised without an actual LLM or `pi` install. The
real `pi` is a TUI; in production the runner will spawn
`pi --mode rpc` (or similar) which speaks JSONL too — the
fake emits JSONL of the same shape so the runner code is
identical for both.

Wire format (stdout, one record per line, LF-terminated):
    {"type": "progress", "kind": "thought", "text": "..."}   x3
    done

The "done" line is plain text (not JSON) so the runner can
distinguish a turn boundary from an event record at a
glance. The runner parses JSONL for events and treats any
non-JSON line as terminal output / control text.
"""
from __future__ import annotations

import json
import sys
import time


def main() -> int:
    """Run one fake pi turn. Returns 0 on success."""
    # Read one line of input; if stdin is closed (e.g. the parent
    # process never sent anything) treat that as an empty prompt.
    line = sys.stdin.readline()
    prompt = line.strip() if line else ""

    # Three progress events, ~100ms apart, with the prompt echoed
    # back in the first one so a test can verify the runner passed
    # the user input through to the child.
    for i in range(3):
        event = {
            "type": "progress",
            "kind": "thought",
            "text": f"step {i + 1} of 3 for: {prompt!r}",
        }
        sys.stdout.write(json.dumps(event) + "\n")
        sys.stdout.flush()
        time.sleep(0.1)

    # Final turn-boundary marker. Plain text so the runner's
    # "is this a JSON event or control text?" check is trivial.
    sys.stdout.write("done\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
