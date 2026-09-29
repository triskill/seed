import asyncio
import functools

import pytest

def async_test(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        return asyncio.run(fn(*args, **kwargs))
    return wrapper

from seed_backend.orchestrator import Orchestrator


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
async def test_failed_start_rolls_back_both_and_allows_retry(failed_role):
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

    (middleman if failed_role == 'middleman' else worker).failure = None
    await orch.start()
    try:
        assert orch.ready
    finally:
        await orch.stop()
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
