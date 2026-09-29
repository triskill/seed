import asyncio
import functools

import pytest

def async_test(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        return asyncio.run(fn(*args, **kwargs))
    return wrapper

from seed_backend.orchestrator import Orchestrator
from seed_backend.pi_runner import PiRunner
import sys
from pathlib import Path


class Runner:
    def __init__(self, failure=None):
        self.failure = failure
        self.started = False
        self.stops = 0
        self.pid = None

    async def start(self):
        if self.failure:
            raise self.failure
        self.started = True
        self.pid = 1

    async def stop(self):
        self.stops += 1
        self.started = False
        self.pid = None

    async def read_lines(self):
        while True:
            import asyncio
            await asyncio.sleep(3600)
            yield 'unused'


@async_test
@pytest.mark.parametrize('failed_role', ['middleman', 'worker'])
async def test_failed_start_rolls_back_both_and_fresh_orchestrator_can_start(failed_role):
    error = RuntimeError('startup failed')
    middleman = Runner(error if failed_role == 'middleman' else None)
    worker = Runner(error if failed_role == 'worker' else None)
    orch = Orchestrator(middleman, worker)
    events = orch.subscribe()
    with pytest.raises(RuntimeError) as caught:
        await orch.start()
    assert caught.value is error
    assert middleman.stops == worker.stops == 1
    assert not middleman.started and not worker.started
    assert orch._read_middleman_task is orch._read_worker_task is None
    assert not orch.ready
    assert [events.get_nowait()['status'] for _ in range(events.qsize())][-2:] == ['unavailable', 'unavailable']

    # PiRunner.stop() closes its executor permanently; replacement needs new runners.
    replacement = Orchestrator(Runner(), Runner())
    await replacement.start()
    try:
        assert replacement.ready
    finally:
        await replacement.stop()
    assert not replacement.ready


@async_test
async def test_cancelled_worker_start_rolls_back_both_and_reraises():
    entered = asyncio.Event()

    class PendingWorker(Runner):
        async def start(self):
            self.pid = 2
            entered.set()
            await asyncio.Future()

    middleman, worker = Runner(), PendingWorker()
    orch = Orchestrator(middleman, worker)
    events = orch.subscribe()
    startup = asyncio.create_task(orch.start())
    await entered.wait()
    startup.cancel()
    with pytest.raises(asyncio.CancelledError):
        await startup
    assert middleman.stops == worker.stops == 1
    assert middleman.pid is worker.pid is None
    assert not orch.ready
    assert orch._read_middleman_task is orch._read_worker_task is None
    assert [events.get_nowait()['status'] for _ in range(events.qsize())][-2:] == ['unavailable', 'unavailable']


@async_test
@pytest.mark.parametrize('unready_role', ['middleman', 'worker'])
async def test_start_rejects_unready_pi_runner_and_rolls_back_both(unready_role):
    cmd = [sys.executable, str(Path(__file__).parent / 'fixtures' / 'fake_pi.py')]

    class UnreadyRunner(PiRunner):
        async def start(self):
            await super().start()
            self._ready_event.clear()

    middleman = (UnreadyRunner if unready_role == 'middleman' else PiRunner)(cmd, role='middleman')
    worker = (UnreadyRunner if unready_role == 'worker' else PiRunner)(cmd, role='worker')
    orch = Orchestrator(middleman, worker)
    with pytest.raises(RuntimeError, match='not ready'):
        await orch.start()
    assert not orch.ready
    assert middleman.pid is worker.pid is None
    assert middleman._executor_shutdown and worker._executor_shutdown


@async_test
async def test_failed_real_worker_start_requires_fresh_pi_runners():
    cmd = [sys.executable, str(Path(__file__).parent / 'fixtures' / 'fake_pi.py')]
    middleman = PiRunner(cmd, role='middleman')
    worker = PiRunner(['/nonexistent/seed-worker-executable'], role='worker')
    orch = Orchestrator(middleman, worker)
    with pytest.raises(FileNotFoundError):
        await orch.start()
    assert not orch.ready
    assert middleman.pid is worker.pid is None
    assert middleman._executor_shutdown and worker._executor_shutdown
    with pytest.raises(RuntimeError, match='cannot be restarted'):
        await middleman.start()

    replacement = Orchestrator(PiRunner(cmd, role='middleman'), PiRunner(cmd, role='worker'))
    try:
        await replacement.start()
        assert replacement.ready
        assert replacement.middleman.pid is not None
        assert replacement.worker.pid is not None
    finally:
        await replacement.stop()
    assert not replacement.ready


@async_test
async def test_ready_broadcast_failure_rolls_back_both():
    middleman, worker = Runner(), Runner()
    orch = Orchestrator(middleman, worker)
    error = RuntimeError('ready broadcast failed')
    original_broadcast = orch._broadcast

    async def fail_ready(event):
        if event.get('status') == 'ready':
            raise error
        await original_broadcast(event)

    orch._broadcast = fail_ready
    with pytest.raises(RuntimeError) as caught:
        await orch.start()
    assert caught.value is error
    assert middleman.stops == worker.stops == 1
    assert not orch.ready
    assert orch._read_middleman_task is orch._read_worker_task is None


@async_test
async def test_read_task_setup_failure_cancels_created_task_and_rolls_back(monkeypatch):
    middleman, worker = Runner(), Runner()
    orch = Orchestrator(middleman, worker)
    error = RuntimeError('worker read setup failed')
    original_create_task = asyncio.create_task

    def fail_worker_task(coro, *, name=None):
        if name == 'orchestrator-read-worker':
            coro.close()
            raise error
        return original_create_task(coro, name=name)

    monkeypatch.setattr(asyncio, 'create_task', fail_worker_task)
    with pytest.raises(RuntimeError) as caught:
        await orch.start()
    assert caught.value is error
    assert middleman.stops == worker.stops == 1
    assert orch._read_middleman_task is orch._read_worker_task is None
    assert not orch.ready


@async_test
async def test_ready_rejects_pi_runner_awaiting_restart_preload():
    cmd = [sys.executable, str(Path(__file__).parent / 'fixtures' / 'fake_pi.py')]
    middleman = PiRunner(cmd, role='middleman')
    worker = PiRunner(cmd, role='worker')
    orch = Orchestrator(middleman, worker)
    try:
        await orch.start()
        assert orch.ready
        # A restarted generation has a live PID before its preload finishes.
        worker._ready_process = None
        worker._ready_event.clear()
        assert worker.pid is not None
        assert not orch.ready
    finally:
        await orch.stop()


@async_test
async def test_cancelled_stop_during_rollback_preserves_startup_error_and_stops_worker():
    error = RuntimeError('startup failed')
    middleman, worker = Runner(), Runner(error)

    async def cancelled_stop():
        middleman.stops += 1
        raise asyncio.CancelledError()

    middleman.stop = cancelled_stop
    orch = Orchestrator(middleman, worker)
    with pytest.raises(RuntimeError) as caught:
        await orch.start()
    assert caught.value is error
    assert middleman.stops == worker.stops == 1
    assert not orch.ready


@async_test
async def test_cleanup_failure_does_not_replace_startup_error():
    error = RuntimeError('startup failed')
    middleman, worker = Runner(), Runner(error)
    async def broken_stop():
        middleman.stops += 1
        raise RuntimeError('cleanup failed')
    middleman.stop = broken_stop
    orch = Orchestrator(middleman, worker)
    with pytest.raises(RuntimeError) as caught:
        await orch.start()
    assert caught.value is error
    assert worker.stops == 1
    assert not orch.ready
