import asyncio
import json

from seed_backend.orchestrator import Orchestrator
from seed_backend.task_store import TaskStore


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


def test_replay_retry_restart_gap_and_privacy(tmp_path):
    async def scenario():
        store = TaskStore(tmp_path)
        orch = Orchestrator(Runner(), Runner(), store)
        await orch._broadcast({'type': 'middleman_line', 'line': 'hello sk-test12345678901234567890'})
        await orch._broadcast({'type': 'worker_line', 'line': 'next'})
        await orch._broadcast({'type': 'error', 'message': 'provider token=secret-value'})
        replay = orch.subscribe(orch.generation_id, 0)
        items = [replay.get_nowait() for _ in range(3)]
        assert [x['eventId'] for x in items] == [1, 2, 3]
        assert [x['type'] for x in items] == ['middleman_line', 'worker_line', 'error']
        assert 'sk-test12345678901234567890' not in str(items)
        assert 'secret-value' not in str(items)
        assert 'hello' in items[0]['line']
        assert orch.subscribe(orch.generation_id, 1).get_nowait()['eventId'] == 2
        orch.task_status = {'type': 'task_status', 'taskId': 't', 'status': 'running'}
        await orch._status('completed', 'token=secret-value')
        await orch._emit_outcome('backend')
        gap = orch.subscribe('old', 3)
        frame = gap.get_nowait()
        assert frame['type'] == 'history_gap'
        assert frame['task']['status'] == 'completed'
        assert frame['outcome']['status'] == 'completed'
        assert gap.empty()
        assert 'secret-value' not in str(frame)
        assert 'secret-value' not in (tmp_path / 'task-events.json').read_text()
        restored = Orchestrator(Runner(), Runner(), store)
        assert restored.generation_id != orch.generation_id
        assert restored._event_id == 0
        old = restored.subscribe(orch.generation_id, 5)
        envelope = old.get_nowait()
        assert envelope['type'] == 'history_gap' and 'eventId' not in envelope
        assert restored._event_id == 0
        assert old.empty()
        assert all(e.get('generationId') != orch.generation_id for e in restored._journal)
    asyncio.run(scenario())


def test_gap_is_out_of_band_and_has_inline_terminal_snapshot():
    async def scenario():
        orch = Orchestrator(Runner(), Runner())
        orch.task_status = {'type': 'task_status', 'taskId': 't', 'status': 'completed'}
        await orch._broadcast({'type': 'task_outcome', 'taskId': 't', 'status': 'completed'})
        cursor = orch._event_id
        first = orch.subscribe('stale', cursor).get_nowait()
        second = orch.subscribe('stale', cursor).get_nowait()
        assert 'eventId' not in first and 'eventId' not in second
        assert orch._event_id == cursor
        assert first['task']['taskId'] == first['outcome']['taskId'] == 't'
        assert all(e['generationId'] == orch.generation_id for e in orch._journal)
    asyncio.run(scenario())


def test_slow_subscriber_gap_does_not_skip_healthy_subscriber_or_replay_ids():
    async def scenario():
        orch = Orchestrator(Runner(), Runner())
        orch._SUBSCRIBER_QUEUE_MAXSIZE = 2
        slow, healthy = orch.subscribe(), orch.subscribe()
        for n in range(5):
            await orch._broadcast({'type': 'middleman_line', 'line': str(n)})
            assert healthy.get_nowait()['eventId'] == n + 1
        gap = slow.get_nowait()
        assert gap['type'] == 'history_gap' and 'eventId' not in gap
        assert orch._event_id == 5
        assert [e['eventId'] for e in orch._journal] == [1, 2, 3, 4, 5]
        replay = orch.subscribe(orch.generation_id, 3)
        assert [replay.get_nowait()['eventId'] for _ in range(2)] == [4, 5]
    asyncio.run(scenario())


def test_dispatch_flushes_previous_outcome_before_new_pending():
    async def scenario():
        orch = Orchestrator(Runner(), Runner())
        q = orch.subscribe()
        orch.task_status = {'type': 'task_status', 'taskId': 'A', 'status': 'running'}
        await orch._status('completed')
        await orch._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'B', 'spec': 'fix'})
        events = list(q._queue)
        outcome = next(i for i, e in enumerate(events) if e['type'] == 'task_outcome' and e['taskId'] == 'A')
        pending = next(i for i, e in enumerate(events) if e['type'] == 'task_status' and e['status'] == 'pending')
        assert outcome < pending
        await orch._emit_outcome('backend')
        assert len([e for e in q._queue if e['type'] == 'task_outcome' and e['taskId'] == 'A']) == 1
    asyncio.run(scenario())


def test_middleman_eof_falls_back_without_waiting_for_timer():
    async def scenario():
        mm = Runner()
        orch = Orchestrator(mm, Runner())
        orch.task_status = {'type': 'task_status', 'taskId': 'A', 'status': 'running'}
        await orch._status('completed')
        await orch._send_terminal_report('Worker task A completed')
        q = orch.subscribe()
        await mm.lines.put(None)
        await orch._read_middleman_loop()
        events = list(q._queue)
        assert any(e['type'] == 'task_outcome' and e['source'] == 'backend' for e in events)
        assert any(e['type'] == 'role_health' and e['status'] == 'unavailable' for e in events)
    asyncio.run(scenario())


def test_task_store_rejects_raw_lines_and_dispatch_even_if_directly_saved(tmp_path):
    store = TaskStore(tmp_path)
    secret = 'unique-raw-private-dispatch-phrase'
    store.save({'type': 'task_status', 'taskId': 't', 'status': 'running', 'summary': secret, 'dispatch': secret})
    store.save_events([{'type': 'worker_line', 'line': secret}, {'type': 'middleman_line', 'line': secret},
                       {'type': 'dispatch', 'text': secret}, {'type': 'task_outcome', 'taskId': 't',
                       'status': 'completed', 'summary': secret}])
    assert secret not in (tmp_path / 'task-status.json').read_text()
    assert secret not in (tmp_path / 'task-events.json').read_text()
    assert all(e['type'] == 'task_outcome' for e in store.load_events())


def test_middleman_final_summary_is_sanitized_and_bounded():
    async def scenario():
        mm, worker = Runner(), Runner()
        orch = Orchestrator(mm, worker)
        await orch.start()
        q = orch.subscribe()
        try:
            await orch._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'x', 'spec': 'fix'})
            await worker.lines.put(json.dumps({'type': 'agent_end', 'messages': [{'role': 'assistant', 'content': [{'type': 'text', 'text': '<task:done summary="Fixed"/>'}]}]}))
            for _ in range(12): await asyncio.sleep(0)
            report = mm.sent[-1]['message']
            await mm.lines.put(json.dumps({'type': 'agent_end', 'messages': [
                {'role': 'user', 'content': [{'type': 'text', 'text': report}]},
                {'role': 'assistant', 'content': [{'type': 'text', 'text': 'Fixed the crash. token=secret-value ' + 'x' * 3000}]}]}))
            for _ in range(12): await asyncio.sleep(0)
            outcome = next(e for e in q._queue if e['type'] == 'task_outcome')
            assert outcome['source'] == 'middleman'
            assert outcome['summary'].startswith('Fixed the crash.')
            assert 'secret-value' not in outcome['summary']
            assert len(outcome['summary']) <= 2048
        finally:
            await orch.stop()
    asyncio.run(scenario())
