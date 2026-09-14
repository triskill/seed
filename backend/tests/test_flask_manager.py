"""Tests for the Flask subprocess manager.

Task 0.5: validates that the FlaskManager can spawn the Flask webapp
subprocess, wait for it to be ready (polling `/api/ping`), and shut it
down cleanly. Exercises the real Flask process so the test catches
both lifecycle bugs and webapp startup regressions.
"""
import asyncio
import socket
import time
from pathlib import Path

import httpx

from seed_backend.flask_manager import FLASK_STARTUP_TIMEOUT_SECONDS, FlaskManager


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


def test_flask_manager_uses_debug_reloader_without_debugger():
    """The worker agent mutates `app.py` while Flask is
    running. The Flask CLI must explicitly enable its reloader while
    disabling the interactive debugger.

    We don't spawn Flask (that would be slow / flaky) — we verify the
    command passed to the child.
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
        captured["args"] = args
        captured["env"] = env
        return FakeProcess()

    async def timed_out_wait_ready(*, timeout):
        captured["readiness_timeout"] = timeout
        raise TimeoutError

    async def scenario():
        manager = FlaskManager(port=7778)
        with (
            patch("asyncio.create_subprocess_exec", side_effect=fake_exec),
            patch.object(manager, "wait_ready", side_effect=timed_out_wait_ready),
        ):
            started = await manager.start()
            # This test pins subprocess environment construction, not socket
            # readiness. Mock the timeout so an unrelated listener on 7778
            # cannot make the result order-dependent.
            assert started is False

    asyncio.run(scenario())
    assert captured["env"] is not None
    assert "--debug" in captured["args"]
    assert "--no-debugger" in captured["args"]
    assert captured["readiness_timeout"] == FLASK_STARTUP_TIMEOUT_SECONDS


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


def test_flask_manager_reloads_worker_python_edit(tmp_path):
    """The real Flask reloader serves a worker edit without restarting FastAPI."""
    app_dir = tmp_path / "worker-app"
    package_dir = app_dir / "seed_app"
    package_dir.mkdir(parents=True)
    (package_dir / "__init__.py").write_text("")
    app_file = package_dir / "app.py"

    def write_app(message: str) -> None:
        app_file.write_text(
            "from flask import Flask\n"
            "app = Flask(__name__)\n"
            "@app.get('/api/ping')\n"
            "def ping():\n"
            "    return {'pong': True}\n"
            "@app.get('/reload-probe')\n"
            "def reload_probe():\n"
            f"    return {message!r}\n"
        )

    write_app("before")
    with socket.socket() as candidate:
        candidate.bind(("127.0.0.1", 0))
        port = candidate.getsockname()[1]

    async def scenario():
        manager = FlaskManager(port=port, app_dir=str(app_dir), poll_interval=0.05)
        try:
            assert await manager.start()
            async with httpx.AsyncClient() as client:
                assert (await client.get(f"http://127.0.0.1:{port}/reload-probe")).text == "before"
                # Werkzeug's filesystem watcher can have one-second timestamp
                # granularity, so ensure the source mtime advances.
                time.sleep(1.1)
                write_app("after")
                deadline = asyncio.get_running_loop().time() + 12
                while True:
                    if (await client.get(f"http://127.0.0.1:{port}/reload-probe")).text == "after":
                        break
                    if asyncio.get_running_loop().time() >= deadline:
                        raise AssertionError("Flask reloader did not serve the worker edit")
                    await asyncio.sleep(0.1)
        finally:
            await manager.stop()

    asyncio.run(scenario())
