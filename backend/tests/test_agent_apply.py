"""Agent-only settings apply keeps the embedded runtime in place."""
from __future__ import annotations

import asyncio
import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS


def test_agent_factory_uses_shared_auth_without_credential_env(monkeypatch):
    for name in PI_CREDENTIAL_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    selection = service.AgentApplyRequest(
        provider="openai", modelId="gpt-test", thinkingLevel="low",
    )

    orchestrator = service._new_orchestrator("http://127.0.0.1:7778", selection)

    assert orchestrator.middleman.cmd[orchestrator.middleman.cmd.index("--model") + 1] == "gpt-test"
    assert orchestrator.worker.cmd[orchestrator.worker.cmd.index("--thinking") + 1] == "low"
    for runner in (orchestrator.middleman, orchestrator.worker):
        assert runner.env is not None
        assert not any(name in runner.env for name in PI_CREDENTIAL_ENV_VARS)


def test_apply_endpoint_replaces_agents_without_returning_api_key(monkeypatch):
    control_app = FastAPI()
    control_app.router.routes.extend(
        route for route in service.app.routes
        if getattr(route, "path", "") == "/control/v1/agents/apply"
    )
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")
    directory = __import__('pathlib').Path(__import__('tempfile').mkdtemp())
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(directory))
    (directory / 'auth.json').write_text(json.dumps({'openai': {'type': 'api_key', 'key': 'secret'}}))
    applied = []

    class Control:
        async def validate_selection(self, *args):
            return {'valid': True}

    control_app.state.control_service = Control()

    async def replace(app, selection):
        applied.append(selection)

    monkeypatch.setattr(service, "_replace_agents", replace)
    with TestClient(control_app, client=("127.0.0.1", 1234)) as client:
        response = client.post(
            "/control/v1/agents/apply",
            headers={"Authorization": "Bearer capability-test"},
            json={
                "provider": "openai",
                "modelId": "gpt-test",
                "thinkingLevel": "low",
            },
        )

    assert response.status_code == 200
    assert response.json() == {"applied": True}
    assert "sk-secret" not in response.text
    assert len(applied) == 1
    assert applied[0].model_id == 'gpt-test'


def test_apply_write_failure_keeps_existing_agents(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setattr(service.pi_settings, 'save_selection', lambda *args: (_ for _ in ()).throw(OSError('disk full')))
    monkeypatch.setattr(service, '_new_orchestrator', lambda *args: pytest.fail('must not spawn agents'))
    app = FastAPI()
    app.state.agent_lock = asyncio.Lock()
    app.state.orchestrator = object()
    with pytest.raises(OSError):
        asyncio.run(service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='test')))
    assert app.state.orchestrator is not None


def test_apply_preserves_live_subscribers_and_interrupts_only_active_task(monkeypatch, tmp_path):
    from seed_backend.orchestrator import Orchestrator
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    class Runner:
        async def start(self): pass
        async def stop(self): pass
    old = Orchestrator(Runner(), Runner())
    old.task_status = {'type': 'task_status', 'taskId': 'task-1', 'status': 'running'}
    queue = old.subscribe()
    queue.get_nowait()
    replacement = Orchestrator(Runner(), Runner())
    old._acceptances['request-1'] = b'fingerprint'
    monkeypatch.setattr(service, '_new_orchestrator', lambda *args: replacement)
    app = FastAPI()
    app.state.agent_lock = asyncio.Lock()
    app.state.orchestrator = old
    asyncio.run(service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='test')))
    assert queue.get_nowait()['status'] == 'interrupted'
    assert queue.get_nowait()['type'] == 'task_outcome'
    assert replacement._subscribers is old._subscribers
    assert replacement._acceptances is old._acceptances
    assert replacement._acceptance_lock is old._acceptance_lock
    assert replacement._acceptances['request-1'] == b'fingerprint'
    assert replacement.task_status['status'] == 'interrupted'
    asyncio.run(replacement._broadcast({'type': 'worker_line', 'text': 'new'}))
    assert any(event.get('text') == 'new' for event in (queue.get_nowait() for _ in range(queue.qsize())))


def test_apply_retains_active_snapshot_when_stop_clears_it(monkeypatch, tmp_path):
    from seed_backend.orchestrator import Orchestrator
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    class Runner:
        def __init__(self): self.lines = asyncio.Queue()
        async def start(self): pass
        async def stop(self): await self.lines.put(None)
        async def read_lines(self):
            while (line := await self.lines.get()) is not None: yield line
    async def scenario():
        old = Orchestrator(Runner(), Runner())
        await old.start()
        old.task_status = {'type': 'task_status', 'taskId': 'live', 'status': 'running'}
        queue = old.subscribe()
        queue.get_nowait()
        original_stop = old.stop
        async def stopping():
            await original_stop()
            old.task_status = None
        old.stop = stopping
        replacement = Orchestrator(Runner(), Runner())
        monkeypatch.setattr(service, '_new_orchestrator', lambda *args: replacement)
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        await service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='test'))
        assert queue.get_nowait()['status'] == 'interrupted'
        assert replacement.task_status['taskId'] == 'live'
        await replacement.stop()
    asyncio.run(scenario())


def test_apply_waits_for_inflight_acceptance_before_stopping_old(monkeypatch, tmp_path):
    from seed_backend.orchestrator import Orchestrator
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    entered = asyncio.Event()
    release = asyncio.Event()
    saved = asyncio.Event()
    original_save = service.pi_settings.save_selection
    def save(*args):
        original_save(*args)
        saved.set()
    monkeypatch.setattr(service.pi_settings, 'save_selection', save)
    calls = []

    class Runner:
        def __init__(self, held=False):
            self.held = held
            self.stopped = False
            self.lines = asyncio.Queue()
        async def start(self): pass
        async def stop(self):
            self.stopped = True
            await self.lines.put(None)
        async def read_lines(self):
            while (line := await self.lines.get()) is not None:
                yield line
        async def rpc_request(self, command):
            calls.append(command['message'])
            if self.held:
                entered.set()
                await release.wait()
            if self.stopped:
                raise RuntimeError('runner stopped before receipt')
            return {'success': True}

    async def scenario():
        old = Orchestrator(Runner(held=True), Runner())
        await old.start()
        new = Orchestrator(Runner(), Runner())
        monkeypatch.setattr(service, '_new_orchestrator', lambda *args: new)
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        accepting = asyncio.create_task(old.accept_user_message('id-1', 'once'))
        await entered.wait()
        applying = asyncio.create_task(service._replace_agents(
            app, service.AgentApplyRequest(provider='openai', modelId='new')))
        # Apply has persisted selection; it must wait for the RPC before stopping.
        await asyncio.wait_for(saved.wait(), 2)
        await asyncio.sleep(0)
        assert old.ready and not old.middleman.stopped
        release.set()
        assert await accepting
        await applying
        assert app.state.orchestrator is new
        assert await new.accept_user_message('id-1', 'once')
        with pytest.raises(RuntimeError, match='Middleman unavailable'):
            await old.accept_user_message('id-2', 'new prompt')
        assert calls == ['once']
        await new.stop()

    asyncio.run(scenario())


def test_apply_status_write_failure_does_not_publish_replacement(monkeypatch, tmp_path):
    from seed_backend.orchestrator import Orchestrator
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    before = {'defaultProvider': 'old', 'defaultModel': 'old-model'}
    (tmp_path / 'settings.json').write_text(json.dumps(before))

    class Runner:
        def __init__(self): self.lines = asyncio.Queue()
        async def start(self): pass
        async def stop(self): await self.lines.put(None)
        async def read_lines(self):
            while (line := await self.lines.get()) is not None:
                yield line

    async def scenario():
        old = Orchestrator(Runner(), Runner())
        await old.start()
        old.task_status = {'type': 'task_status', 'taskId': 'live', 'status': 'running'}
        replacement = Orchestrator(Runner(), Runner())
        restored = Orchestrator(Runner(), Runner())
        class FailingStore:
            def save_events(self, events): pass
            def save(self, status):
                raise OSError('disk full')
        replacement.task_store = FailingStore()
        generated = iter((replacement, restored))
        monkeypatch.setattr(service, '_new_orchestrator', lambda *args: next(generated))
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        with pytest.raises(OSError, match='disk full'):
            await service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='new'))
        assert app.state.orchestrator is not replacement
        assert app.state.orchestrator is restored and restored.ready
        assert not replacement.ready
        assert json.loads((tmp_path / 'settings.json').read_text()) == before
        await restored.stop()

    asyncio.run(scenario())


def test_apply_constructor_failure_restores_settings_without_stopping_old(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    before = {'defaultProvider': 'old', 'defaultModel': 'old-model'}
    (tmp_path / 'settings.json').write_text(json.dumps(before))
    old = object()
    app = FastAPI()
    app.state.agent_lock = asyncio.Lock()
    app.state.orchestrator = old
    monkeypatch.setattr(service, '_new_orchestrator', lambda *args: (_ for _ in ()).throw(RuntimeError('construction failed')))
    with pytest.raises(RuntimeError, match='construction failed'):
        asyncio.run(service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='new')))
    assert app.state.orchestrator is old
    assert json.loads((tmp_path / 'settings.json').read_text()) == before


@pytest.mark.parametrize('rollback_fails', [False, True])
def test_apply_failed_replacement_never_exposes_stopped_agents(monkeypatch, tmp_path, rollback_fails):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    selection = service.AgentApplyRequest(provider='openai', modelId='test')
    (tmp_path / 'settings.json').write_text('{}')

    class Agents:
        def __init__(self, fail_start=False):
            self.ready = False
            self.fail_start = fail_start
            self.stops = 0
            self.starts = 0
            self.task_status = None
            self._active = False
            self._subscribers = set()
            self._acceptances = {}
            self._acceptance_lock = asyncio.Lock()

        async def start(self):
            self.starts += 1
            if self.fail_start:
                raise RuntimeError('pi failed')
            self.ready = True

        async def stop(self):
            self.stops += 1
            self.ready = False

    async def scenario():
        old = Agents()
        await old.start()
        replacement = Agents(fail_start=True)
        restored = Agents(fail_start=rollback_fails)
        constructed = []
        def factory(url, configured=None):
            constructed.append(configured)
            return replacement if configured is selection else restored
        monkeypatch.setattr(service, '_new_orchestrator', factory)
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        with pytest.raises(RuntimeError, match='pi failed'):
            await service._replace_agents(app, selection)
        assert old.stops == 1 and old.starts == 1  # stopped PiRunner never restarted
        assert replacement.stops >= 1
        assert constructed == [selection, None]
        assert app.state.orchestrator is (None if rollback_fails else restored)
        assert json.loads((tmp_path / 'settings.json').read_text()) == {}
    asyncio.run(scenario())


def test_failed_apply_recreates_real_pi_runners(monkeypatch, tmp_path):
    import sys
    from pathlib import Path
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    before = {'defaultProvider': 'old', 'defaultModel': 'old-model'}
    (tmp_path / 'settings.json').write_text(json.dumps(before))
    fake = [sys.executable, str(Path(__file__).parent / 'fixtures' / 'fake_pi.py')]
    monkeypatch.setattr(service, 'pi_cmd_for_role', lambda role, **kwargs: fake)
    factory = service._new_orchestrator
    def make(url, selection=None):
        if selection is not None and selection.provider == 'new':
            raise_after_start = factory(url, selection)
            async def fail():
                raise RuntimeError('new generation failed')
            raise_after_start.start = fail
            return raise_after_start
        return factory(url, selection)
    monkeypatch.setattr(service, '_new_orchestrator', make)
    async def scenario():
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        old = factory('http://127.0.0.1:7778')
        await old.start()
        app.state.orchestrator = old
        old_pid = old.middleman.pid
        try:
            with pytest.raises(RuntimeError, match='new generation failed'):
                await service._replace_agents(app, service.AgentApplyRequest(provider='new', modelId='new-model'))
            recovered = app.state.orchestrator
            assert recovered is not old and recovered.ready
            assert old.middleman.pid is old.worker.pid is None
            assert recovered.middleman.pid != old_pid
            assert json.loads((tmp_path / 'settings.json').read_text()) == before
        finally:
            if app.state.orchestrator is not None:
                await app.state.orchestrator.stop()
    asyncio.run(scenario())


def test_apply_failure_recreates_previous_selection_and_retains_subscribers(monkeypatch, tmp_path):
    from seed_backend.orchestrator import Orchestrator
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    before = {'defaultProvider': 'old', 'defaultModel': 'old-model', 'defaultThinkingLevel': 'high'}
    (tmp_path / 'settings.json').write_text(json.dumps(before))

    class Runner:
        def __init__(self, fails=False):
            self.fails = fails
            self.started = False
            self.stopped = False
        async def start(self):
            if self.stopped:
                raise RuntimeError('cannot restart')
            if self.fails:
                raise RuntimeError('replacement failed')
            self.started = True
        async def stop(self):
            self.stopped = True
            self.started = False
        async def read_lines(self):
            while not self.stopped:
                await asyncio.sleep(0.01)
                if False: yield ''

    async def scenario():
        old = Orchestrator(Runner(), Runner())
        await old.start()
        queue = old.subscribe()
        failed = Orchestrator(Runner(fails=True), Runner())
        recovered = Orchestrator(Runner(), Runner())
        selections = []
        def factory(url, selection=None):
            selections.append(selection)
            return failed if len(selections) == 1 else recovered
        monkeypatch.setattr(service, '_new_orchestrator', factory)
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        with pytest.raises(RuntimeError, match='replacement failed'):
            await service._replace_agents(app, service.AgentApplyRequest(provider='new', modelId='new-model'))
        assert old.middleman.stopped and old.worker.stopped
        assert app.state.orchestrator is recovered and recovered.ready
        assert selections[1].provider == 'old'
        assert selections[1].model_id == 'old-model'
        assert selections[1].thinking_level == 'high'
        assert recovered._subscribers is old._subscribers
        while not queue.empty(): queue.get_nowait()
        await recovered._broadcast({'type': 'worker_line', 'text': 'back'})
        assert queue.get_nowait()['text'] == 'back'
        assert json.loads((tmp_path / 'settings.json').read_text()) == before
        await recovered.stop()
    asyncio.run(scenario())


def test_failed_apply_notifies_existing_subscriber_of_interrupted_task_once(monkeypatch, tmp_path):
    from seed_backend.orchestrator import Orchestrator
    from seed_backend.task_store import TaskStore

    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    store = TaskStore(tmp_path)

    class Runner:
        def __init__(self, fails=False):
            self.fails = fails
            self.lines = asyncio.Queue()
        async def start(self):
            if self.fails:
                raise RuntimeError('replacement failed')
        async def stop(self):
            await self.lines.put(None)
        async def read_lines(self):
            while (line := await self.lines.get()) is not None:
                yield line

    async def scenario():
        old = Orchestrator(Runner(), Runner(), task_store=store)
        await old.start()
        old.task_status = {'type': 'task_status', 'taskId': 'live', 'status': 'running'}
        store.save(old.task_status)
        queue = old.subscribe()
        queue.get_nowait()  # initial task snapshot
        saves = []
        original_save = store.save
        def save(status):
            saves.append(dict(status))
            original_save(status)
        monkeypatch.setattr(store, 'save', save)
        failed = Orchestrator(Runner(fails=True), Runner())
        recovered = None
        calls = 0
        def factory(*args):
            nonlocal recovered, calls
            calls += 1
            if calls == 1:
                return failed
            recovered = Orchestrator(Runner(), Runner(), task_store=store)
            return recovered
        monkeypatch.setattr(service, '_new_orchestrator', factory)
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        with pytest.raises(RuntimeError, match='replacement failed'):
            await service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='new'))
        assert app.state.orchestrator is recovered
        terminal = [queue.get_nowait() for _ in range(queue.qsize())]
        terminal = [event for event in terminal if event['type'] in ('task_status', 'task_outcome')]
        assert [(event['type'], event['taskId'], event['status']) for event in terminal] == [
            ('task_status', 'live', 'interrupted'),
            ('task_outcome', 'live', 'interrupted'),
        ]
        assert len(saves) == 1  # recovered constructor persisted interruption already
        assert recovered.task_status['status'] == 'interrupted'
        await recovered.stop()

    asyncio.run(scenario())


def test_orchestrator_stop_attempts_both_runners_when_first_fails():
    from seed_backend.orchestrator import Orchestrator
    class Runner:
        def __init__(self, fails=False):
            self.fails = fails
            self.stopped = False
        async def stop(self):
            self.stopped = True
            if self.fails:
                raise OSError('first stop failed')
    async def scenario():
        first, second = Runner(True), Runner()
        agents = Orchestrator(first, second)
        with pytest.raises(OSError, match='first stop failed'):
            await agents.stop()
        assert first.stopped and second.stopped
    asyncio.run(scenario())


def test_apply_stop_failure_preserves_original_error_and_cleans_replacement(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    class Agents:
        def __init__(self, fail_stop=False):
            self.ready = True
            self.task_status = None
            self._active = False
            self.stops = 0
            self.fail_stop = fail_stop
            self._subscribers = set()
            self._acceptances = {}
            self._acceptance_lock = asyncio.Lock()
        async def stop(self):
            self.stops += 1
            self.ready = False
            if self.fail_stop:
                raise RuntimeError('old stop failed')
        async def start(self):
            self.ready = True
    old = Agents(fail_stop=True)
    new = Agents()
    restored = Agents()
    calls = []
    def factory(url, selection=None):
        calls.append(selection)
        return new if len(calls) == 1 else restored
    monkeypatch.setattr(service, '_new_orchestrator', factory)
    app = FastAPI()
    app.state.agent_lock = asyncio.Lock()
    app.state.orchestrator = old
    with pytest.raises(RuntimeError, match='old stop failed'):
        asyncio.run(service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='new')))
    assert new.stops >= 1
    assert old.stops >= 1
    assert app.state.orchestrator is restored
    assert json.loads((tmp_path / 'settings.json').read_text()) == {}


def test_apply_settings_restore_failure_does_not_advertise_old_agents(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setattr(service.pi_settings, 'restore_settings',
                        lambda *args: (_ for _ in ()).throw(OSError('disk full')))

    class Agents:
        def __init__(self, fails=False):
            self.ready = True
            self.fails = fails
            self.stops = 0
            self.task_status = None
            self._active = False
            self._acceptance_lock = asyncio.Lock()

        async def start(self):
            if self.fails:
                raise RuntimeError('new agents failed')
            self.ready = True

        async def stop(self):
            self.stops += 1
            self.ready = False

    async def scenario():
        old, replacement = Agents(), Agents(fails=True)
        monkeypatch.setattr(service, '_new_orchestrator', lambda *args: replacement)
        app = FastAPI()
        app.state.agent_lock = asyncio.Lock()
        app.state.orchestrator = old
        with pytest.raises(RuntimeError, match='new agents failed'):
            await service._replace_agents(app, service.AgentApplyRequest(provider='openai', modelId='test'))
        assert app.state.orchestrator is None
        assert not old.ready and old.stops >= 1
    asyncio.run(scenario())


def test_apply_accepts_native_oauth_provider_in_catalog(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setenv('SEED_RUNTIME_CAPABILITY', 'capability-test')
    (tmp_path / 'auth.json').write_text(json.dumps({'shell-oauth': {'type': 'oauth', 'refresh': 'token'}}))
    app = FastAPI()
    app.router.routes.extend(r for r in service.app.routes if getattr(r, 'path', '') == '/control/v1/agents/apply')

    class Control:
        async def validate_selection(self, provider, model, level):
            assert (provider, model) == ('shell-oauth', 'catalog-model')
            return {'valid': True}

    app.state.control_service = Control()

    async def replace(app, selection):
        assert selection.provider == 'shell-oauth'

    monkeypatch.setattr(service, '_replace_agents', replace)
    with TestClient(app, client=('127.0.0.1', 1234)) as client:
        response = client.post('/control/v1/agents/apply',
                               headers={'Authorization': 'Bearer capability-test'},
                               json={'provider': 'shell-oauth', 'modelId': 'catalog-model'})
    assert response.status_code == 200


def test_apply_endpoint_rejects_unknown_provider(monkeypatch):
    control_app = FastAPI()
    control_app.router.routes.extend(
        route for route in service.app.routes
        if getattr(route, "path", "") == "/control/v1/agents/apply"
    )
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")

    with TestClient(control_app, client=("127.0.0.1", 1234)) as client:
        response = client.post(
            "/control/v1/agents/apply",
            headers={"Authorization": "Bearer capability-test"},
            json={
                "provider": "unknown",
                "modelId": "x",
                "thinkingLevel": "off",
            },
        )

    assert response.status_code == 422
    assert 'apiKey' not in response.text
