import asyncio
import json

from fastapi import WebSocketDisconnect

from seed_backend.chat import handle_chat


def run_async(fn):
    def test():
        asyncio.run(fn())
    return test


class Socket:
    def __init__(self, frames):
        self.frames = list(frames)
        self.sent = []

    async def accept(self):
        pass

    async def receive_text(self):
        if self.frames:
            return json.dumps(self.frames.pop(0))
        await asyncio.sleep(0.02)
        raise WebSocketDisconnect()

    async def send_text(self, text):
        self.sent.append(json.loads(text))


class Orchestrator:
    def __init__(self):
        self.calls = []
        self.queues = []
        self.reject = False

    def subscribe(self, generation_id=None, event_id=None):
        self.calls.append(('subscribe', generation_id, event_id))
        q = asyncio.Queue()
        self.queues.append(q)
        q.put_nowait({'type': 'history_gap' if generation_id else 'task_status'})
        return q

    def unsubscribe(self, queue):
        self.calls.append(('unsubscribe', queue))

    async def accept_user_message(self, request_id, text):
        await self.send_to_middleman(text)
        return True

    async def send_to_middleman(self, text):
        self.calls.append(('prompt', text))
        if self.reject:
            raise RuntimeError('private exception details')

    async def _broadcast(self, event):
        self.calls.append(('broadcast', event))


@run_async
async def test_resume_subscribes_once_without_initial_snapshot():
    socket = Socket([{'type': 'resume', 'generationId': 'old', 'eventId': 4},
                     {'type': 'resume', 'generationId': 'old', 'eventId': 4}])
    orch = Orchestrator()
    await handle_chat(socket, orch)
    assert orch.calls[0] == ('subscribe', 'old', 4)
    assert len(orch.queues) == 1
    assert not any(event['type'] == 'task_status' for event in socket.sent)


@run_async
async def test_legacy_user_message_subscribes_and_does_not_ack():
    socket = Socket([{'type': 'user_message', 'text': 'hello'}])
    orch = Orchestrator()
    await handle_chat(socket, orch)
    assert orch.calls[0] == ('subscribe', None, None)
    assert ('prompt', 'hello') in orch.calls
    assert not any(event['type'] == 'user_ack' for event in socket.sent)


@run_async
async def test_requested_message_rejection_ack_safe_and_connection_survives():
    socket = Socket([{'type': 'user_message', 'text': 'hello', 'requestId': 'a'},
                     {'type': 'user_message', 'text': 'again', 'requestId': 'b'}])
    orch = Orchestrator()
    orch.reject = True
    await handle_chat(socket, orch)
    assert [e for e in socket.sent if e['type'] == 'user_ack'] == [
        {'type': 'user_ack', 'requestId': 'a', 'accepted': False, 'reason': 'Middleman unavailable'},
        {'type': 'user_ack', 'requestId': 'b', 'accepted': False, 'reason': 'Middleman unavailable'},
    ]
    assert len([c for c in orch.calls if c[0] == 'prompt']) == 2


@run_async
async def test_requested_message_acceptance_ack_once():
    socket = Socket([{'type': 'user_message', 'text': 'hello', 'requestId': 'a'}])
    orch = Orchestrator()
    await handle_chat(socket, orch)
    assert [e for e in socket.sent if e['type'] == 'user_ack'] == [
        {'type': 'user_ack', 'requestId': 'a', 'accepted': True}
    ]
