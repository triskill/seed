"""Tests for the Flask subprocess manager.

Task 0.5: validates that the FlaskManager can spawn the Flask webapp
subprocess, wait for it to be ready (polling `/api/ping`), and shut it
down cleanly. Exercises the real Flask process so the test catches
both lifecycle bugs and webapp startup regressions.
"""
import asyncio

import httpx

from seed_backend.flask_manager import FlaskManager


def test_flask_manager_starts_and_stops():
    """Manager starts Flask, /api/ping returns 200, stop terminates cleanly."""
    async def scenario():
        manager = FlaskManager(port=7778, app_dir="/home/borbot/prg/seed/webapp")
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
        with patch(
            "asyncio.create_subprocess_exec", side_effect=fake_exec
        ):
            started = await manager.start()
            # FakeProcess never actually binds 127.0.0.1:7778, so
            # wait_ready times out and start() returns False.
            # The captured env must still have FLASK_DEBUG=1 set
            # (this is the only thing we're pinning in this test).
            assert started is False

    asyncio.run(scenario())
    assert captured["env"] is not None
    assert captured["env"].get("FLASK_DEBUG") == "1"
