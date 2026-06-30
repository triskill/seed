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
        manager = FlaskManager(port=7778)
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
