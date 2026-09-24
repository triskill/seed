import asyncio
import json
class Runner:
    def __init__(self):
        self.lines = asyncio.Queue()
        self.sent = []

    async def start(self): pass
    async def stop(self): await self.lines.put(None)
    async def rpc_request(self, command):
        self.sent.append(command)
        return {'success': True}
    async def read_lines(self):
        while (line := await self.lines.get()) is not None:
            yield line

async def tick():
    for _ in range(8):
        await asyncio.sleep(0)
from seed_backend.orchestrator import Orchestrator


def test_report_reply_wins_and_other_turn_does_not():
    async def scenario():
        mm, worker = Runner(), Runner()
        orch = Orchestrator(mm, worker)
        await orch.start()
        q = orch.subscribe()
        try:
            await orch._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'x', 'spec': 'fix'})
            task_id = orch.task_status['taskId']
            await worker.lines.put(json.dumps({'type': 'agent_end', 'messages': [{'role': 'assistant', 'content': [{'type': 'text', 'text': '<task:done summary="Fixed"/>'}]}]}))
            await tick()
            assert not [e for e in list(q._queue) if e['type'] == 'task_outcome']
            await mm.lines.put(json.dumps({'type': 'agent_end', 'messages': [{'role': 'user', 'content': [{'type': 'text', 'text': 'another turn'}]}, {'role': 'assistant', 'content': [{'type': 'text', 'text': 'Unrelated'}]}]}))
            await tick()
            assert not [e for e in list(q._queue) if e['type'] == 'task_outcome']
            await mm.lines.put(json.dumps({'type': 'agent_end', 'messages': [{'role': 'user', 'content': [{'type': 'text', 'text': mm.sent[-1]['message']}]}, {'role': 'assistant', 'content': [{'type': 'text', 'text': 'Done and verified'}]}]}))
            await tick()
            outcomes = [e for e in list(q._queue) if e['type'] == 'task_outcome']
            assert len(outcomes) == 1 and outcomes[0]['source'] == 'middleman'
            assert outcomes[0]['summary'] == 'Done and verified'
        finally:
            await orch.stop()
    asyncio.run(scenario())
