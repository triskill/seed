#!/usr/bin/env python3
"""Fake pi variant that writes ANSI color codes (Task 2.3).

Like the basic fake_pi.py, this reads one line of user
input from stdin and writes a couple of progress events
to stdout. The difference: the first event's text is
wrapped in a red CSI sequence (`ESC[31m...ESC[0m`) so
the runner's ANSI-strip code has something to chew on.

Used by `test_ansi_strip.py`. Spawned the same way as
the plain fake pi (`[sys.executable, this_file]`).
"""
from __future__ import annotations

import json
import sys
import time


def main() -> int:
    """Run one fake-pi turn that emits an ANSI-coloured line."""
    line = sys.stdin.readline()
    prompt = line.strip() if line else ""

    # First event: red-coloured text. The runner should
    # strip the ESC bytes before yielding to the consumer.
    event1 = {
        "type": "progress",
        "kind": "thought",
        "text": "red text",  # the runner sees this stripped of colour
    }
    # Build the line with embedded ANSI: ESC[31m + text + ESC[0m.
    payload = "\x1b[31m" + json.dumps(event1) + "\x1b[0m" + "\n"
    sys.stdout.write(payload)
    sys.stdout.flush()
    time.sleep(0.1)

    # Second event: plain (no colour) so we can verify
    # the stripper doesn't mangle non-coloured text.
    event2 = {
        "type": "progress",
        "kind": "thought",
        "text": f"step 2 of 2 for: {prompt!r}",
    }
    sys.stdout.write(json.dumps(event2) + "\n")
    sys.stdout.flush()
    time.sleep(0.05)

    sys.stdout.write("done\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
