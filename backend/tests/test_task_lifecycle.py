import asyncio
import json


from seed_backend.orchestrator import Orchestrator


class Runner:
    def __init__(self):
        self.lines = asyncio.Queue()
        self.sent = []

    async def start(self): pass
    async def stop(self): await self.lines.put(None)
    async def send(self, command): self.sent.append(json.loads(command))
    async def rpc_request(self, command, *, timeout=30.0):
        self.sent.append(command)
        return {'type': 'response', 'id': 'test', 'success': True}
    async def read_lines(self):
        while (line := await self.lines.get()) is not None:
            yield line


async def tick():
    for _ in range(8):
        await asyncio.sleep(0)


async def _test_middleman_provider_failure_is_visible_without_leaking_details():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    queue = orch.subscribe()
    try:
        await mm.lines.put(json.dumps({'type': 'agent_end', 'messages': [
            {'role': 'assistant', 'stopReason': 'error', 'errorMessage': '400 secret output-limit detail'}
        ]}))
        await tick()
        events = [queue.get_nowait() for _ in range(queue.qsize())]
        errors = [event for event in events if event['type'] == 'error']
        assert len(errors) == 1
        assert 'secret' not in errors[0]['message']
        assert 'model' in errors[0]['message'].lower()
    finally:
        await orch.stop()


async def _test_dispatch_snapshot_progress_and_settlement():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    q = orch.subscribe()
    try:
        await mm.lines.put('```json\n{"intent":"fix_bug","feature":"x","spec":"fix"}\n```')
        await tick()
        assert [event['status'] for event in (q.get_nowait() for _ in range(q.qsize())) if event['type'] == 'task_status'] == ['pending', 'running']
        task_id = orch.task_status['taskId']
        snapshot = orch.subscribe().get_nowait()
        assert {k: v for k, v in snapshot.items() if k not in ('generationId', 'eventId')} == {'type': 'task_status', 'taskId': task_id, 'status': 'running'}
        await worker.lines.put(json.dumps({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':'<task:done summary="Fixed"/>'}}))
        await tick()
        assert orch.task_status['status'] == 'running'
        await worker.lines.put(json.dumps({'type':'agent_end','willRetry':True,'messages':[]}))
        await tick()
        assert orch.task_status['status'] == 'running'
        await worker.lines.put(json.dumps({'type':'agent_end','willRetry':False,'messages':[{'role':'assistant','stopReason':'stop','content':[{'type':'text','text':'<task:done summary="Fixed"/>'}]}]}))
        await tick()
        assert orch.task_status['status'] == 'completed'
        await worker.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert orch.task_status == {'type':'task_status','taskId':task_id,'status':'completed','summary':'Fixed'}
        assert any('Fixed' in x['message'] for x in mm.sent)
    finally:
        await orch.stop()


async def _test_concurrent_dispatch_failure_and_stop():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    try:
        spec = {'intent':'fix_bug','feature':'x','spec':'fix'}
        await orch._send_dispatch_to_worker(spec)
        first = orch.task_status['taskId']
        await orch._send_dispatch_to_worker(spec)
        assert orch.task_status['taskId'] == first
        assert len(worker.sent) == 1
        await orch.stop_task()
        await tick()
        assert worker.sent[-1]['type'] == 'abort'
        await worker.lines.put(json.dumps({'type':'agent_end','willRetry':False,'messages':[]}))
        await tick()
        assert orch.task_status['status'] == 'cancelled'
        await worker.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert orch.task_status['status'] == 'cancelled'
    finally:
        await orch.stop()


async def _test_progress_throttle_and_retry():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    try:
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'x','spec':'fix'})
        task_id = orch.task_status['taskId']
        async def delta(text):
            await worker.lines.put(json.dumps({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':text}}))
            await tick()
        await delta('<task:progress taskId="wrong">wrong</task:progress>')
        await delta(f'<task:progress taskId="{task_id}">phase one</task:progress>')
        await delta(f'<task:progress taskId="{task_id}">phase two</task:progress>')
        assert sum('Worker progress' in x.get('message','') for x in mm.sent) == 1
        await worker.lines.put(json.dumps({'type':'agent_end','willRetry':True,'messages':[{'role':'assistant','stopReason':'error','errorMessage':'SECRET'}]}))
        await tick()
        assert orch.task_status['status'] == 'running'
        await delta('<task:done summary="Recovered"/>')
        await worker.lines.put(json.dumps({'type':'agent_end','messages':[{'role':'assistant','stopReason':'stop','content':[{'type':'text','text':'Recovered'}]}]}))
        await worker.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert orch.task_status['status'] == 'completed'
        assert 'SECRET' not in str(mm.sent)
    finally:
        await orch.stop()


async def _test_worker_error_and_middleman_steering():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    try:
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'x','spec':'fix'})
        task_id = orch.task_status['taskId']
        await orch.send_to_middleman('change direction')
        assert mm.sent[-1]['type'] == 'prompt' and mm.sent[-1]['streamingBehavior'] == 'followUp'
        await mm.lines.put('```json\n'+json.dumps({'type':'steer_worker','taskId':task_id,'message':'use blue'})+'\n```')
        await tick()
        assert worker.sent[-1] == {'type':'steer','message':'use blue'}
        await worker.lines.put(json.dumps({'type':'agent_end','willRetry':False,'messages':[{'role':'assistant','stopReason':'error','errorMessage':'SECRET'}]}))
        await tick()
        assert orch.task_status['status'] == 'failed'
        await worker.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert orch.task_status['status'] == 'failed'
        assert 'SECRET' not in str(orch.task_status)
    finally:
        await orch.stop()


async def _test_failed_rpc_and_missing_authoritative_end():
    mm, worker = Runner(), Runner()
    async def reject(command, *, timeout=30.0):
        worker.sent.append(command)
        return {'type': 'response', 'success': False, 'error': 'PRIVATE'}
    worker.rpc_request = reject
    orch = Orchestrator(mm, worker)
    await orch.start()
    try:
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'x','spec':'fix'})
        assert orch.task_status['status'] == 'failed'
        assert 'PRIVATE' not in str(orch.task_status)
        worker.rpc_request = Runner().rpc_request
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'x','spec':'fix'})
        await worker.lines.put(json.dumps({'type':'message_update','assistantMessageEvent':{'type':'text_delta','delta':'<task:done/>'}}))
        await worker.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert orch.task_status['status'] == 'failed'
    finally:
        await orch.stop()


async def _test_abort_does_not_block_and_timeout_restarts_worker():
    mm, worker = Runner(), Runner()
    waiting = asyncio.Event()
    async def hung(command, *, timeout=30.0):
        if command['type'] == 'abort':
            await waiting.wait()
        worker.sent.append(command)
        return {'success': True}
    worker.rpc_request = hung
    orch = Orchestrator(mm, worker)
    await orch.start()
    try:
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'x','spec':'fix'})
        await asyncio.wait_for(orch.stop_task(), .2)
        assert orch.task_status['status'] == 'cancel_pending'
        orch._cancel_timeout_task.cancel()
        await orch._cancel_fallback(orch.task_status['taskId'], delay=0)
        assert orch.task_status['status'] == 'interrupted'
        assert not orch._worker_blocked
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'next','spec':'fix'})
        assert orch.task_status['status'] == 'running'
    finally:
        waiting.set()
        await orch.stop()

async def _test_error_in_earlier_message_prevents_completion():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    try:
        await orch._send_dispatch_to_worker({'intent':'fix_bug','feature':'x','spec':'fix'})
        await worker.lines.put(json.dumps({'type':'agent_end','messages':[
            {'role':'assistant','stopReason':'error','errorMessage':'PRIVATE'},
            {'role':'assistant','stopReason':'stop','content':[{'type':'text','text':'<task:done summary="Done"/>'}]},
        ]}))
        await worker.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert orch.task_status['status'] == 'failed'
    finally:
        await orch.stop()


async def _test_middleman_context_error_without_settled():
    mm, worker = Runner(), Runner()
    orch = Orchestrator(mm, worker)
    await orch.start()
    q = orch.subscribe()
    try:
        await mm.lines.put(json.dumps({'type':'agent_end','willRetry':True,'messages':[{'role':'assistant','stopReason':'error','errorMessage':'maximum context length PRIVATE'}]}))
        await tick()
        assert q.empty()
        await mm.lines.put(json.dumps({'type':'agent_end','willRetry':False,'messages':[{'role':'assistant','stopReason':'error','errorMessage':'maximum context length PRIVATE'}]}))
        await tick()
        errors = [q.get_nowait() for _ in range(q.qsize())]
        assert len(errors) == 1
        assert errors[0]['type'] == 'error'
        assert 'context/output limit' in errors[0]['message']
        assert 'PRIVATE' not in errors[0]['message']
        await mm.lines.put(json.dumps({'type':'agent_settled'}))
        await tick()
        assert q.empty()
    finally:
        await orch.stop()


def test_middleman_context_error_without_settled(): asyncio.run(_test_middleman_context_error_without_settled())
def test_abort_does_not_block_and_timeout_restarts_worker(): asyncio.run(_test_abort_does_not_block_and_timeout_restarts_worker())
def test_error_in_earlier_message_prevents_completion(): asyncio.run(_test_error_in_earlier_message_prevents_completion())
def test_failed_rpc_and_missing_authoritative_end(): asyncio.run(_test_failed_rpc_and_missing_authoritative_end())
def test_middleman_provider_failure_is_visible_without_leaking_details(): asyncio.run(_test_middleman_provider_failure_is_visible_without_leaking_details())
def test_dispatch_snapshot_progress_and_settlement(): asyncio.run(_test_dispatch_snapshot_progress_and_settlement())
def test_concurrent_dispatch_failure_and_stop(): asyncio.run(_test_concurrent_dispatch_failure_and_stop())
def test_worker_error_and_middleman_steering(): asyncio.run(_test_worker_error_and_middleman_steering())
def test_progress_throttle_and_retry(): asyncio.run(_test_progress_throttle_and_retry())
