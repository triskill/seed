#!/usr/bin/env python3
"""Fake pi variant that emits a tool_execution_start event (Task 2.4).

On startup, reads its first command-line argument as
the tool name to advertise. Then writes a plain "ok"
line (so the consumer has something to anchor on), the
tool event itself, and a final "done" marker. The
runner-under-test uses the tool name to decide whether
to block.

The tool event shape mirrors real pi's RPC mode
(`{"type": "tool_execution_start", "toolCallId":
"call_xxx", "toolName": "<arg>", "args": {...}}`) so
the runner's filter code paths line up with the wire
format pi actually uses.
"""
from __future__ import annotations

import json
import sys
import time


def main() -> int:
    """Run one fake-pi turn that emits a tool event."""
    tool_name = sys.argv[1] if len(sys.argv) > 1 else "bash"

    # Plain text marker so the consumer can see the
    # turn started before the (potentially-blocked)
    # tool event arrives.
    sys.stdout.write("ok\n")
    sys.stdout.flush()
    time.sleep(0.05)

    # The tool event. Args are deliberately minimal;
    # the filter only looks at `toolName`.
    event = {
        "type": "tool_execution_start",
        "toolCallId": "call_fake_1",
        "toolName": tool_name,
        "args": {"_": "fake"},
    }
    sys.stdout.write(json.dumps(event) + "\n")
    sys.stdout.flush()
    time.sleep(0.05)

    sys.stdout.write("done\n")
    sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
