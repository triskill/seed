import asyncio
import json
from seed_backend.orchestrator import Orchestrator
from seed_backend.task_store import TaskStore

class Runner:
    async def rpc_request(self, command):
        return {'success': True}

def test_private_safe_replay_and_restart(tmp_path):
    async def scenario():
        store = TaskStore(tmp_path)
        orch = Orchestrator(Runner(), Runner(), store)
        await orch._broadcast({'type': 'middleman_line', 'line': 'SECRET token'})
        orch.task_status = {'type': 'task_status', 'taskId': 'id', 'status': 'running'}
        await orch._status('completed', 'OK')
        assert 'SECRET' not in (tmp_path / 'task-events.json').read_text()
        restored = Orchestrator(Runner(), Runner(), store)
        q = restored.subscribe('old', 1)
        gap = q.get_nowait()
        assert gap['type'] == 'history_gap'
        assert gap['task']['type'] == 'task_status'
        assert gap['outcome']['type'] == 'task_outcome'
        assert q.empty()
    asyncio.run(scenario())

def test_slow_subscriber_gap():
    async def scenario():
        orch = Orchestrator(Runner(), Runner())
        orch._SUBSCRIBER_QUEUE_MAXSIZE = 2
        q = orch.subscribe()
        for n in range(3):
            await orch._broadcast({'type': 'middleman_line', 'line': str(n)})
        assert q.get_nowait()['type'] == 'history_gap'
    asyncio.run(scenario())
