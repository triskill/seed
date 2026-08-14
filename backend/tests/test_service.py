"""Tests for the FastAPI orchestrator service.

Task 0.2: smoke-tests the `/health` endpoint. Task 0.5 extends the
response to include the Flask subprocess status. The TestClient context
manager triggers the lifespan, which actually starts a real Flask
subprocess on port 7778 — the test then asserts both that the
orchestrator is up and that Flask reports ready.
"""
from fastapi.testclient import TestClient

from seed_backend.service import _app_url_for_mode, app


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



def test_app_url_uses_host_flask_port_when_subprocess_is_running(monkeypatch):
    monkeypatch.delenv("SEED_APP_URL", raising=False)
    assert _app_url_for_mode(
        flask_subprocess_running=True,
    ) == "http://127.0.0.1:7778"


def test_app_url_uses_embedded_fastapi_port_for_wsgi_mount(monkeypatch):
    monkeypatch.delenv("SEED_APP_URL", raising=False)
    assert _app_url_for_mode(
        flask_subprocess_running=False,
    ) == "http://127.0.0.1:7777"


def test_app_url_explicit_override_wins_in_both_modes(monkeypatch):
    monkeypatch.setenv("SEED_APP_URL", "http://127.0.0.1:9000/")
    assert _app_url_for_mode(
        flask_subprocess_running=True,
    ) == "http://127.0.0.1:9000"
    assert _app_url_for_mode(
        flask_subprocess_running=False,
    ) == "http://127.0.0.1:9000"
