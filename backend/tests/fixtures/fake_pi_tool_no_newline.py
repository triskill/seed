"""Emit a disallowed tool event without a trailing newline."""
import json
import sys

sys.stdout.write(json.dumps({
    "type": "tool_execution_start",
    "toolName": "bash",
}))
sys.stdout.flush()
