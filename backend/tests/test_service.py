"""Tests for the FastAPI orchestrator service.

Task 0.2: smoke-tests the `/health` endpoint that confirms the service is
running. The endpoint is the foundation for everything in Phase 1+ (shell,
WebSocket chat, pi runner), so it must be reliable and trivial.
"""
from fastapi.testclient import TestClient

from seed_backend.service import app

client = TestClient(app)


def test_health_returns_200_ok():
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}
