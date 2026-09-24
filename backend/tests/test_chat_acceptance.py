import asyncio
import json

from fastapi import WebSocketDisconnect

from seed_backend.chat import handle_chat
from seed_backend.orchestrator import Orchestrator


class Runner:
    def __init__(self):
        self.calls = []
        self.gate = None
        self.reject = False

    async def rpc_request(self, command):
        self.calls.append(command)
        if self.gate:
            await self.gate.wait()
        return {'success': not self.reject}


class Socket:
    def __init__(self, message):
        self.message = message
        self.sent = []

    async def accept(self):
        pass

    async def receive_text(self):
        if self.message is not None:
            message, self.message = self.message, None
            return json.dumps(message)
        await asyncio.sleep(.02)
        raise WebSocketDisconnect()

    async def send_text(self, text):
        self.sent.append(json.loads(text))


def test_reconnect_concurrency_mismatch_and_distinct_ids(caplog):
    async def scenario():
        runner = Runner()
        orch = Orchestrator(runner, Runner())
        runner.gate = asyncio.Event()
        secret = 'private-secret-prompt'
        frame = {'type': 'user_message', 'text': secret, 'requestId': 'id-1'}
        first, retry = Socket(frame), Socket(frame)
        a = asyncio.create_task(handle_chat(first, orch))
        await asyncio.sleep(.005)
        b = asyncio.create_task(handle_chat(retry, orch))
        await asyncio.sleep(.005)
        assert len(runner.calls) == 1
        runner.gate.set()
        await asyncio.gather(a, b)
        assert len(runner.calls) == 1
        assert all({'type': 'user_ack', 'requestId': 'id-1', 'accepted': True} in s.sent for s in (first, retry))
        mismatch = Socket({**frame, 'text': 'different'})
        distinct = Socket({**frame, 'requestId': 'id-2'})
        await handle_chat(mismatch, orch)
        await handle_chat(distinct, orch)
        assert len(runner.calls) == 2
        assert any(e['type'] == 'user_ack' and e['accepted'] is False for e in mismatch.sent)
        assert secret not in caplog.text
    asyncio.run(scenario())


def test_rejected_and_invalid_requests_not_cached():
    async def scenario():
        runner = Runner()
        orch = Orchestrator(runner, Runner())
        frame = {'type': 'user_message', 'text': 'hello', 'requestId': 'id'}
        runner.reject = True
        rejected = Socket(frame)
        await handle_chat(rejected, orch)
        assert any(e['type'] == 'user_ack' and e['accepted'] is False for e in rejected.sent)
        runner.reject = False
        invalid = Socket({**frame, 'text': None})
        await handle_chat(invalid, orch)
        accepted = Socket(frame)
        await handle_chat(accepted, orch)
        assert len(runner.calls) == 2
        assert any(e['type'] == 'user_ack' and e['accepted'] is True for e in accepted.sent)
    asyncio.run(scenario())
