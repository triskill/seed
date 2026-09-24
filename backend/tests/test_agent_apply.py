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
