import json
import os
from fastapi import FastAPI
from fastapi.testclient import TestClient

from seed_backend.orchestrator import pi_env_for_role
from seed_backend.service import app
from seed_backend import pi_settings
from concurrent.futures import ThreadPoolExecutor


def test_shared_directory_and_no_child_secrets(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path / 'agent'))
    monkeypatch.setenv('OPENAI_API_KEY', 'secret')
    monkeypatch.setenv('SEED_RUNTIME_CAPABILITY', 'cap')
    for role in ('control', 'middleman', 'worker'):
        env = pi_env_for_role(role, provider='openai')
        assert env['PI_CODING_AGENT_DIR'] == str(tmp_path / 'agent')
        assert 'OPENAI_API_KEY' not in env
        assert 'SEED_RUNTIME_CAPABILITY' not in env


def test_config_routes_persist_native_auth_and_settings(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setenv('SEED_RUNTIME_CAPABILITY', 'cap')
    (tmp_path / 'auth.json').write_text(json.dumps({'other': {'type': 'oauth', 'refresh': 'keep'}}))
    (tmp_path / 'settings.json').write_text(json.dumps({'theme': 'keep'}))
    api = FastAPI()
    api.router.routes.extend(r for r in app.routes if getattr(r, 'path', '').startswith('/control/v1/'))
    with TestClient(api, client=('127.0.0.1', 1234)) as client:
        headers = {'Authorization': 'Bearer cap'}
        assert client.get('/control/v1/config').status_code == 401
        response = client.post('/control/v1/providers', json={'provider': 'openai', 'apiKey': 'secret'}, headers=headers)
        assert response.status_code == 200
        assert response.json()['providers'] == ['openai', 'other']
        assert 'secret' not in response.text
        assert client.get('/control/v1/config', headers=headers).json()['providers'] == ['openai', 'other']
    assert json.loads((tmp_path / 'auth.json').read_text()) == {'other': {'type': 'oauth', 'refresh': 'keep'}, 'openai': {'type': 'api_key', 'key': 'secret'}}
    assert os.stat(tmp_path / 'auth.json').st_mode & 0o777 == 0o600
    assert json.loads((tmp_path / 'settings.json').read_text()) == {'theme': 'keep'}


def test_config_get_reflects_shell_edits_on_next_request(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setenv('SEED_RUNTIME_CAPABILITY', 'cap')
    api = FastAPI()
    api.router.routes.extend(r for r in app.routes if getattr(r, 'path', '') == '/control/v1/config')
    with TestClient(api, client=('127.0.0.1', 1234)) as client:
        headers = {'Authorization': 'Bearer cap'}
        assert 'defaultModel' not in client.get('/control/v1/config', headers=headers).json()
        (tmp_path / 'settings.json').write_text(json.dumps({'defaultModel': 'shell-model'}))
        assert client.get('/control/v1/config', headers=headers).json()['defaultModel'] == 'shell-model'


def test_parallel_provider_writes_preserve_all_entries(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    with ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda provider: pi_settings.save_provider(provider, 'secret'),
                      ('openai', 'anthropic', 'google', 'groq', 'xai', 'mistral')))
    assert set(pi_settings.config()['providers']) == {'openai', 'anthropic', 'google', 'groq', 'xai', 'mistral'}


def test_shell_write_during_save_is_not_overwritten(monkeypatch, tmp_path):
    import pytest
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    (tmp_path / 'settings.json').write_text(json.dumps({'theme': 'old'}))
    original = pi_settings.write_json

    def shell_interleaves(name, data, *, expected):
        (tmp_path / name).write_text(json.dumps({'theme': 'shell'}))
        return original(name, data, expected=expected)

    monkeypatch.setattr(pi_settings, 'write_json', shell_interleaves)
    with pytest.raises(pi_settings.ConfigConflictError):
        pi_settings.save_selection('openai', 'model', None)
    assert json.loads((tmp_path / 'settings.json').read_text()) == {'theme': 'shell'}


def test_credential_post_models_is_removed():
    assert not any(getattr(route, 'path', '') == '/control/v1/models' and 'POST' in route.methods
                   for route in app.routes)


def test_apply_rejects_credential_field():
    from pydantic import ValidationError
    from seed_backend.service import AgentApplyRequest
    import pytest
    with pytest.raises(ValidationError):
        AgentApplyRequest(provider='openai', modelId='gpt-test', apiKey='secret')
