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

    async def send(self, command: str) -> None:
        self.sent.append(command)


def test_malformed_dispatch_does_not_stop_middleman_loop(caplog) -> None:
    lines = [
        "```json",
        "{not valid json}",
        "```",
        "still streaming",
        "```json",
        '{"intent": "build_feature", "feature": "notes"}',
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

    assert events == [
        {"type": WS_TYPE_MIDDLEMAN_LINE, "line": line}
        for line in lines
    ]
    assert len(worker.sent) == 1
    command = json.loads(worker.sent[0])
    assert command["type"] == "prompt"
    assert json.loads(command["message"]) == {
        "intent": "build_feature",
        "feature": "notes",
    }
    assert "malformed middle-man dispatch" in caplog.text
