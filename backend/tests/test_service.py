"""Tests for the FastAPI orchestrator service.

Task 0.2: smoke-tests the `/health` endpoint. Task 0.5 extends the
response to include the Flask subprocess status. The TestClient context
manager triggers the lifespan, which actually starts a real Flask
subprocess on port 7778 — the test then asserts both that the
orchestrator is up and that Flask reports ready.
"""
from fastapi.testclient import TestClient

from seed_backend.service import app


def test_health_reports_status_and_flask_up():
    """`/health` returns 200 with status=ok and flask=up after lifespan startup."""
    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "ok", "flask": "up"}
