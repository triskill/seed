"""Regression tests for malformed middle-man dispatch recovery."""
from __future__ import annotations

import asyncio
import json
import logging

from seed_backend.events import WS_TYPE_MIDDLEMAN_LINE
from seed_backend.orchestrator import Orchestrator


class StubMiddleman:
    def __init__(self, lines: list[str]) -> None:
        self.lines = lines

    async def read_lines(self):
        for line in self.lines:
            yield line


class RecordingWorker:
    def __init__(self) -> None:
        self.sent: list[str] = []

    async def rpc_request(self, command: dict, *, timeout: float = 30.0) -> dict:
        self.sent.append(command)
        return {"type": "response", "success": True}


def test_malformed_dispatch_does_not_stop_middleman_loop(caplog) -> None:
    lines = [
        "```json",
        "{not valid json}",
        "```",
        "still streaming",
        "```json",
        json.dumps({'type': 'message_update', 'assistantMessageEvent': {'type': 'text_delta', 'delta': '{"intent": "build_feature", "feature": "notes", "spec": "Add notes"}\n'}}),
        "```",
    ]
    middleman = StubMiddleman(lines)
    worker = RecordingWorker()
    orchestrator = Orchestrator(middleman=middleman, worker=worker)
    subscriber = orchestrator.subscribe()

    with caplog.at_level(logging.WARNING):
        asyncio.run(orchestrator._read_middleman_loop())

    events = []
    while not subscriber.empty():
        events.append(subscriber.get_nowait())

    assert [{k: v for k, v in e.items() if k not in ('generationId', 'eventId')} for e in events if e["type"] == WS_TYPE_MIDDLEMAN_LINE] == [
        {"type": WS_TYPE_MIDDLEMAN_LINE, "line": (
            json.loads(line)["assistantMessageEvent"]["delta"] if "message_update" in line else line
        )}
        for line in lines
    ]
    assert [e["status"] for e in events if e["type"] == "task_status"] == ["failed", "pending", "running"]
    assert len(worker.sent) == 1
    command = worker.sent[0]
    assert command["type"] == "prompt"
    assert json.loads(command["message"]) == {
        "intent": "build_feature",
        "feature": "notes",
        "spec": "Add notes",
        "taskId": orchestrator.task_status["taskId"],
    }
    assert "malformed middle-man dispatch" in caplog.text
