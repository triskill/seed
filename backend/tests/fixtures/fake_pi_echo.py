#!/usr/bin/env python3
"""Fake pi that echoes stdin on stdout, for the system-prompt tests (Task 2.5).

Reads every line of stdin and writes it back to
stdout prefixed with 'echo: '. Exits when stdin is
closed (the runner closes the stdin pipe
when it calls stop()).

The 'echo: ' prefix is what the prompt tests grep
for. Keep it stable; the tests depend on it.
"""
from __future__ import annotations

import sys


def main() -> int:
    """Read all of stdin, echo each line to stdout."""
    for line in sys.stdin:
        # line already has the trailing newline from
        # stdin; preserve it so an empty line in stdin
        # shows up as an empty echo.
        sys.stdout.write("echo: ")
        sys.stdout.write(line)
        sys.stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
