import asyncio
from seed_backend.orchestrator import Orchestrator

class Runner:
    async def rpc_request(self, command):
        return {'success': True}

def test_replay_and_terminal_once(tmp_path):
    asyncio.run(check_replay())

async def check_replay():
    orch = Orchestrator(Runner(), Runner())
    q = orch.subscribe()
    await orch._broadcast({'type': 'middleman_line', 'line': 'hello'})
    first = await q.get()
    assert first['generationId'] and first['eventId'] == 1
    replay = orch.subscribe(first['generationId'], 0)
    assert (await replay.get())['type'] == 'middleman_line'
    orch.task_status = {'type': 'task_status', 'taskId': 't', 'status': 'running'}
    await orch._status('completed', 'done')
    await orch._status('failed', 'late')
    # A successful task waits for the Middleman's final reply; simulate fallback.
    await orch._emit_outcome('backend')
    events = [await q.get(), await q.get()]
    assert [e['type'] for e in events] == ['task_status', 'task_outcome']
    assert events[-1]['source'] == 'backend'
    assert events[-1]['status'] == 'completed'
    assert q.empty()
    gap = orch.subscribe('old-generation', 1)
    assert (await gap.get())['type'] == 'history_gap'
