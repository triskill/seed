"""Tests for the Seed v0.1 Flask app.

Task 0.4: formal Flask tests for the `/` and `/api/ping` routes that
were added in Task 0.1. The `/api/ping` endpoint is the readiness signal
the backend's `FlaskManager.wait_ready` polls (Task 0.5+), and `/` is the
placeholder card the Android WebView loads on first launch.
"""
from seed_app.app import app

client = app.test_client()


def test_ping_returns_pong():
    """GET /api/ping returns 200 with body `{"pong": true}`."""
    r = client.get("/api/ping")

    assert r.status_code == 200
    assert r.get_json() == {"pong": True}


def test_index_returns_hello_card():
    """GET / renders the placeholder card with the 'Hello, what should I become?' title."""
    r = client.get("/")

    assert r.status_code == 200
    assert "Hello, what should I become?" in r.get_data(as_text=True)
