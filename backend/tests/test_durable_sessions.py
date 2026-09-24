import asyncio
import json
from pathlib import Path

from seed_backend.orchestrator import Orchestrator, pi_cmd_for_role
from seed_backend.service import _new_orchestrator
from seed_backend.task_store import TaskStore


class Runner:
    def __init__(self):
        self.sent = []
        self.lines = asyncio.Queue()

    async def start(self): pass
    async def stop(self): await self.lines.put(None)
    async def send(self, data): self.sent.append(json.loads(data))
    async def rpc_request(self, payload, *, timeout=30.0):
        self.sent.append(payload)
        return {'type': 'response', 'success': True}
    async def read_lines(self):
        while (line := await self.lines.get()) is not None:
            yield line


def test_role_sessions_are_explicit_and_control_ephemeral(tmp_path, monkeypatch):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    from seed_backend import pi_settings
    monkeypatch.setattr(pi_settings, 'agent_dir', lambda: tmp_path)
    monkeypatch.setattr('seed_backend.orchestrator.agent_dir', lambda: tmp_path)
    orch = _new_orchestrator('http://localhost')
    assert orch.middleman.cmd != orch.worker.cmd
    for role, runner in [('middleman', orch.middleman), ('worker', orch.worker)]:
        cmd = runner.cmd
        assert '--no-session' not in cmd
        assert cmd[cmd.index('--session-id') + 1] == orch.role_session_id(role)
        assert cmd[cmd.index('--session-dir') + 1] == str(tmp_path / 'role-sessions' / role)
    assert '--no-session' in pi_cmd_for_role('control')
    again = _new_orchestrator('http://localhost')
    assert again.role_session_id('worker') == orch.role_session_id('worker')
    assert again.worker.cmd == orch.worker.cmd


def test_active_task_interrupted_on_restart_and_snapshot(tmp_path):
    async def scenario():
        store = TaskStore(tmp_path)
        first = Orchestrator(Runner(), Runner(), task_store=store)
        await first.start()
        await first._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'x', 'spec': 'fix'})
        task_id = first.task_status['taskId']
        assert json.loads((tmp_path / 'task-status.json').read_text())['status'] == 'running'
        await first.stop()
        second = Orchestrator(Runner(), Runner(), task_store=store)
        assert second.subscribe().get_nowait() == {'type': 'task_status', 'taskId': task_id, 'status': 'interrupted', 'summary': 'Task interrupted by restart'}
        await second.start()
        assert second.worker.sent == []
        await second.stop()
    asyncio.run(scenario())


def test_stop_has_pending_state_and_durable_settlement(tmp_path):
    async def scenario():
        orch = Orchestrator(Runner(), Runner(), task_store=TaskStore(tmp_path))
        await orch.start()
        await orch._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'x', 'spec': 'fix'})
        await orch.stop_task()
        assert orch.task_status['status'] == 'cancel_pending'
        for _ in range(8): await asyncio.sleep(0)
        assert orch.worker.sent[-1]['type'] == 'abort'
        assert orch.middleman.sent == []
        await orch.worker.lines.put(json.dumps({'type': 'agent_settled'}))
        for _ in range(12): await asyncio.sleep(0)
        assert orch.task_status['status'] == 'cancelled'
        assert TaskStore(tmp_path).load()['status'] == 'cancelled'
        await orch.stop()
    asyncio.run(scenario())


def test_corrupt_task_record_is_not_resumed(tmp_path):
    (tmp_path / 'task-status.json').write_text('{invalid')
    assert TaskStore(tmp_path).load() is None
