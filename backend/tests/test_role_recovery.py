import asyncio
import sys
from pathlib import Path

import pytest

from seed_backend.orchestrator import Orchestrator
from seed_backend.pi_runner import PiRunner
import functools


def async_test(fn):
    @functools.wraps(fn)
    def wrapper(*args, **kwargs):
        return asyncio.run(fn(*args, **kwargs))
    return wrapper

FIXTURE = str(Path(__file__).parent / 'fixtures/fake_pi_recovery.py')


def runner(role, mode='healthy'):
    return PiRunner([sys.executable, FIXTURE, mode], role,
                    read_only_tools={'read'} if role == 'middleman' else None)


async def until(predicate):
    async def wait():
        while not predicate():
            await asyncio.sleep(.01)
    await asyncio.wait_for(wait(), 4)


@async_test
@pytest.mark.parametrize('role,mode', [('middleman', 'violation'), ('worker', 'eof')])
async def test_subprocess_failure_reaped_and_replaced_without_replay(monkeypatch, tmp_path, role, mode):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (0, 0, 0), raising=False)
    old = runner(role, mode)
    made = []
    def factory(name, previous):
        assert previous.pid is None and previous._executor_shutdown
        new = runner(name)
        new.env = {**__import__('os').environ, 'RECOVERY_LOG': str(tmp_path / 'commands')}
        made.append(new)
        return new
    orch = Orchestrator(old if role == 'middleman' else runner('middleman'),
                        old if role == 'worker' else runner('worker'), runner_factory=factory)
    q = orch.subscribe()
    try:
        await orch.start()
        if role == 'worker':
            await orch._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'x', 'spec': 'x'})
        else:
            await orch.send_to_middleman('failed request')
        await until(lambda: bool(made) and orch.ready)
        assert getattr(orch, role) is made[0]
        if role == 'worker':
            assert orch.task_status['status'] == 'failed'
            events = [q.get_nowait() for _ in range(q.qsize())]
            assert len([e for e in events if e['type'] == 'task_outcome']) == 1
        assert not made[0]._rpc_pending
        import json
        commands = [json.loads(line) for line in (tmp_path / 'commands').read_text().splitlines()]
        assert [cmd['type'] for cmd in commands] == ['get_state']
    finally:
        await orch.stop()


@async_test
@pytest.mark.parametrize('mode', ['bad', 'silent'])
async def test_bad_replacements_exhaust_budget_and_are_reaped(monkeypatch, mode):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (0, 0, 0), raising=False)
    monkeypatch.setattr(Orchestrator, '_STARTUP_RPC_TIMEOUT', .05)
    made = []
    def factory(role, old):
        assert old.pid is None
        new = runner(role, mode)
        made.append(new)
        return new
    orch = Orchestrator(runner('middleman', 'violation'), runner('worker'), runner_factory=factory)
    try:
        await orch.start()
        await orch.send_to_middleman('fail')
        await until(lambda: len(made) == 3 and all(r._executor_shutdown for r in made))
        assert not orch.ready
        assert orch._middleman_unavailable
    finally:
        await orch.stop()


@async_test
async def test_shutdown_collects_replacement_startup(monkeypatch):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (0, 0, 0), raising=False)
    made = []
    def factory(role, old):
        new = runner(role, 'silent')
        made.append(new)
        return new
    orch = Orchestrator(runner('middleman', 'violation'), runner('worker'), runner_factory=factory)
    await orch.start()
    await orch.send_to_middleman('fail')
    await until(lambda: bool(made) and made[0].pid is not None)
    await asyncio.wait_for(orch.stop(), 3)
    assert len(made) == 1
    assert made[0].pid is None and made[0]._executor_shutdown
    assert not orch.ready


@async_test
async def test_default_clone_retains_spawn_identity_and_allowlist(monkeypatch):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (0, 0, 0))
    old = runner('middleman', 'violation')
    old.cmd += ['--session-id', 'persistent-id', '--provider', 'configured', '--thinking', 'low']
    old.env = dict(__import__('os').environ)
    old.max_restarts = 7
    old.strip_ansi = False
    orch = Orchestrator(old, runner('worker'))
    try:
        await orch.start()
        await orch.send_to_middleman('fail')
        await until(lambda: orch.middleman is not old and orch.ready)
        new = orch.middleman
        for field in ('cmd', 'env', 'role', 'system_prompt', 'read_only_tools', 'max_restarts', 'strip_ansi', 'auto_restart'):
            assert getattr(new, field) == getattr(old, field)
        # The retained filter remains fatal on the replacement, too.
        await orch.send_to_middleman('fail again')
        await until(lambda: orch._recoveries['middleman'] == 2 and orch.ready)
        assert new._executor_shutdown
    finally:
        await orch.stop()


@async_test
async def test_stop_during_backoff_never_spawns(monkeypatch):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (1, 1, 1))
    made = []
    def factory(role, old):
        made.append(role)
        return runner(role)
    orch = Orchestrator(runner('middleman', 'violation'), runner('worker'), runner_factory=factory)
    await orch.start()
    await orch.send_to_middleman('fail')
    await until(lambda: orch._recoveries['middleman'] == 1)
    await orch.stop()
    assert not made


@async_test
async def test_other_role_recovery_does_not_release_readiness(monkeypatch):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (0, 0, 0))
    monkeypatch.setattr(Orchestrator, '_STARTUP_RPC_TIMEOUT', .1)
    orch = Orchestrator(runner('middleman'), runner('worker'),
                        runner_factory=lambda role, old: runner(role, 'silent' if role == 'middleman' else 'healthy'))
    try:
        await orch.start()
        orch._schedule_recovery('middleman')
        orch._schedule_recovery('worker')
        await until(lambda: orch._recoveries['worker'] == 1 and not orch._worker_blocked)
        assert not orch.ready
        assert orch._middleman_unavailable
    finally:
        await orch.stop()


@async_test
async def test_cancel_fallback_and_eof_share_one_replacement(monkeypatch):
    monkeypatch.setattr(Orchestrator, '_RECOVERY_BACKOFF', (.05, 0, 0))
    made = []
    def factory(role, old):
        assert old.pid is None
        new = runner(role)
        made.append(new)
        return new
    orch = Orchestrator(runner('middleman'), runner('worker'), runner_factory=factory)
    try:
        await orch.start()
        await orch._send_dispatch_to_worker({'intent': 'fix_bug', 'feature': 'x', 'spec': 'x'})
        orch._cancel_requested = True
        await orch._cancel_fallback(orch.task_status['taskId'], delay=0)
        orch._schedule_recovery('worker')
        await until(lambda: bool(made) and orch.ready)
        assert len(made) == 1
        assert orch.task_status['status'] == 'interrupted'
    finally:
        await orch.stop()
