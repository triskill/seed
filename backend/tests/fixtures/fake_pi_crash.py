#!/usr/bin/env python3
"""Fake pi that crashes after emitting a few events (Task 2.6).

Used by the auto-restart tests. The fixture reads a
generation number from argv[1] (so the test can tell
generations apart), emits two progress events labeled
with the generation, then exits with code 1 (a
"crash"). The runner's auto-restart logic respawns
the child, and we can count distinct generations in
the output to verify restart actually happened.

The output is intentionally short and predictable so
the test assertions are clear:
    gen=<N>: step 1
    gen=<N>: step 2
"""
from __future__ import annotations

import json
import sys
import time


def main() -> int:
    """Run one fake-pi turn that crashes after 2 events.

    The 'generation' label defaults to the process's
    pid (so a test sees a distinct generation per
    fork) and can be overridden via argv[1] (used by
    the test that wants deterministic gen numbers).
    """
    import os as _os
    if len(sys.argv) > 1 and sys.argv[1].isdigit():
        generation = sys.argv[1]
    else:
        generation = str(_os.getpid())

    for i in range(1, 3):
        event = {
            "type": "progress",
            "kind": "thought",
            "text": f"gen={generation}: step {i}",
        }
        sys.stdout.write(json.dumps(event) + "\n")
        sys.stdout.flush()
        time.sleep(0.05)

    # Crash with a non-zero exit code so the runner
    # sees an abnormal exit and (with auto_restart)
    # decides to respawn. We don't flush a "done"
    # marker; the absence of "done" is what the
    # crash-mode test asserts on.
    sys.stdout.flush()
    return 1


if __name__ == "__main__":
    sys.exit(main())
