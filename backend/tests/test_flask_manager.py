"""Tests for the Flask subprocess manager.

Task 0.5: validates that the FlaskManager can spawn the Flask webapp
subprocess, wait for it to be ready (polling `/api/ping`), and shut it
down cleanly. Exercises the real Flask process so the test catches
both lifecycle bugs and webapp startup regressions.
"""
import asyncio
from pathlib import Path

import httpx

from seed_backend.flask_manager import FlaskManager


def test_flask_manager_starts_and_stops():
    """Manager starts Flask, /api/ping returns 200, stop terminates cleanly."""
    async def scenario():
        app_dir = Path(__file__).resolve().parents[2] / "webapp"
        manager = FlaskManager(port=7778, app_dir=str(app_dir))
        try:
            await manager.start()
            await manager.wait_ready(timeout=15)
            async with httpx.AsyncClient() as client:
                r = await client.get("http://127.0.0.1:7778/api/ping")
                assert r.status_code == 200
                assert r.json() == {"pong": True}
        finally:
            await manager.stop()

    asyncio.run(scenario())


def test_flask_manager_enables_flask_debug_for_reload():
    """The worker agent mutates `app.py` while Flask is
    running. Without debug mode, the Werkzeug reloader
    doesn't watch the file and new routes don't appear
    until restart. The manager must set `FLASK_DEBUG=1`
    in the subprocess env so worker edits are picked up
    on the next request.

    We don't spawn Flask (that would be slow / flaky) —
    we just verify the env dict that *would* be passed
    to the child has `FLASK_DEBUG=1` set.
    """
    from unittest.mock import patch
    captured: dict = {}

    class FakeProcess:
        returncode = None
        pid = 12345

        async def wait(self):
            return 0

        def terminate(self):
            self.returncode = 0

        async def communicate(self):
            return b"", b""

    async def fake_exec(*args, env=None, **kwargs):
        captured["env"] = env
        return FakeProcess()

    async def scenario():
        manager = FlaskManager(port=7778)
        with (
            patch("asyncio.create_subprocess_exec", side_effect=fake_exec),
            patch.object(manager, "wait_ready", side_effect=TimeoutError),
        ):
            started = await manager.start()
            # This test pins subprocess environment construction, not socket
            # readiness. Mock the timeout so an unrelated listener on 7778
            # cannot make the result order-dependent.
            assert started is False

    asyncio.run(scenario())
    assert captured["env"] is not None
    assert captured["env"].get("FLASK_DEBUG") == "1"


def test_flask_manager_prefers_seed_app_path_environment(monkeypatch, tmp_path):
    """An explicit app path is shared by Flask and the worker agent."""
    app_dir = tmp_path / "custom-webapp"
    monkeypatch.setenv("SEED_APP_PATH", str(app_dir))

    assert FlaskManager().app_dir == str(app_dir)


def test_flask_manager_finds_repository_webapp_without_cwd_dependency(
    monkeypatch,
    tmp_path,
):
    """A source checkout resolves its sibling webapp from this module's path."""
    monkeypatch.delenv("SEED_APP_PATH", raising=False)
    monkeypatch.chdir(tmp_path)
    expected = Path(__file__).resolve().parents[2] / "webapp"

    assert expected.is_dir()
    assert FlaskManager._default_app_dir() == str(expected)
