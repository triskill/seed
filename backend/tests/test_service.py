"""Tests for the FastAPI orchestrator service.

Task 0.2: smoke-tests the `/health` endpoint. Task 0.5 extends the
response to include the Flask subprocess status. The TestClient context
manager triggers the lifespan, which actually starts a real Flask
subprocess on port 7778 — the test then asserts both that the
orchestrator is up and that Flask reports ready.
"""
import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from seed_backend.flask_manager import FlaskManager
from seed_backend.service import _app_url, app, health


def test_health_reports_status_and_flask_up():
    """`/health` returns 200 with status=ok and flask=up after lifespan startup."""
    # The lifespan starts the FlaskManager with an explicit `app_dir`
    # pointing at the host's `webapp/` package; the default
    # `/home/seed/app` only exists in the embedded runtime.
    import os
    from seed_backend.flask_manager import FlaskManager
    FlaskManager.__init__.__defaults__ = (7778, "127.0.0.1", 0.2, "/home/borbot/prg/seed/webapp")
    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "flask": "up"}



@pytest.mark.parametrize(
    ('outcome', 'agents_ready', 'expected_status', 'expected_flask'),
    [
        ('healthy', True, 200, 'up'),
        ('healthy', False, 503, 'up'),
        ('non200', True, 503, 'down'),
        ('timeout', True, 503, 'down'),
        ('connection', True, 503, 'down'),
    ],
)
def test_health_requires_serving_flask_and_ready_agents(
    monkeypatch, outcome, agents_ready, expected_status, expected_flask,
):
    """Exercise the HTTP route with a live parent and fresh ping per request."""
    isolated = FastAPI()
    isolated.add_api_route('/health', health, methods=['GET'])
    manager = FlaskManager(port=18123)

    class LiveProcess:
        def poll(self):
            return None

    manager._process = LiveProcess()
    isolated.state.flask_manager = manager
    isolated.state.orchestrator = type('Agents', (), {'ready': agents_ready})()
    real_client = httpx.AsyncClient
    requests = []

    def client_factory(*args, **kwargs):
        async def respond(request):
            requests.append(request.url)
            if outcome == 'timeout':
                raise httpx.ReadTimeout('ping timed out', request=request)
            if outcome == 'connection':
                raise httpx.ConnectError('worker disconnected', request=request)
            return httpx.Response(200 if outcome == 'healthy' else 503)

        return real_client(transport=httpx.MockTransport(respond), *args, **kwargs)

    monkeypatch.setattr('seed_backend.flask_manager.httpx.AsyncClient', client_factory)
    with TestClient(isolated) as client:
        response = client.get('/health')
    assert response.status_code == expected_status
    assert response.json() == {
        'status': 'ok' if expected_status == 200 else 'unavailable',
        'flask': expected_flask,
    }
    assert [str(url) for url in requests] == ['http://127.0.0.1:18123/api/ping']


def test_app_url_defaults_to_separate_flask_port(monkeypatch):
    monkeypatch.delenv("SEED_APP_URL", raising=False)
    assert _app_url() == "http://127.0.0.1:7778"


def test_app_url_explicit_override_wins(monkeypatch):
    monkeypatch.setenv("SEED_APP_URL", "http://127.0.0.1:9000/")
    assert _app_url() == "http://127.0.0.1:9000"
