"""Flask webapp subprocess manager.

Task 0.5: spawns the Flask webapp as a subprocess and tracks its
readiness by polling `/api/ping`. The orchestrator (service.py) owns
the FlaskManager via a FastAPI lifespan so the webapp starts when the
backend starts and stops when the backend stops. The pi runner and
shell (later tasks) will reuse the same subprocess-management
patterns.

The manager is intentionally small: start, wait_ready, stop, is_up.
Higher-level concerns (restart on crash, log capture) are deferred to
later tasks.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import httpx


class FlaskManager:
    """Lifecycle wrapper around the Flask webapp subprocess.

    Attributes:
        port:           TCP port Flask binds to.
        host:           Hostname Flask binds to. Loopback by default;
                        the orchestrator only talks to the webapp
                        locally.
        poll_interval:  Seconds between `/api/ping` polls while
                        waiting for readiness.
    """

    def __init__(
        self,
        port: int = 7778,
        host: str = "127.0.0.1",
        poll_interval: float = 0.2,
    ) -> None:
        self.port = port
        self.host = host
        self.poll_interval = poll_interval
        self._process: asyncio.subprocess.Process | None = None

    async def start(self) -> None:
        """Spawn the Flask webapp subprocess. No-op if already started.

        The venv's `flask` binary is located by prepending the directory
        of the current Python interpreter to PATH. This makes the
        manager robust to the venv being activated differently in
        different environments.
        """
        if self._process is not None:
            return

        env = os.environ.copy()
        venv_bin = str(Path(sys.executable).parent)
        env["PATH"] = venv_bin + os.pathsep + env.get("PATH", "")
        # The worker agent mutates app.py while Flask is
        # running. Without debug mode, the Werkzeug
        # reloader doesn't watch the file and the new
        # routes don't appear until restart — and the
        # worker can't restart Flask (the orchestrator
        # supervises it, killing the process from the
        # agent's bash would leave the webapp down
        # until the next server restart). FLASK_DEBUG=1
        # enables the reloader so a worker edit is
        # picked up on the next request.
        env["FLASK_DEBUG"] = "1"

        self._process = await asyncio.create_subprocess_exec(
            "flask",
            "--app", "seed_app.app",
            "run",
            "--host", self.host,
            "--port", str(self.port),
            stdout=asyncio.subprocess.DEVNULL,
            stderr=asyncio.subprocess.PIPE,
            env=env,
        )

    async def wait_ready(self, timeout: float = 10.0) -> None:
        """Poll `/api/ping` until it returns 200 or the timeout elapses.

        Raises:
            TimeoutError: if Flask does not become ready within
                `timeout` seconds.
        """
        url = f"http://{self.host}:{self.port}/api/ping"
        loop = asyncio.get_event_loop()
        deadline = loop.time() + timeout
        async with httpx.AsyncClient() as client:
            while loop.time() < deadline:
                try:
                    response = await client.get(url, timeout=1.0)
                    if response.status_code == 200:
                        return
                except (httpx.RequestError, httpx.HTTPError):
                    # Connection refused, timeout, etc. — Flask not
                    # ready yet, keep polling.
                    pass
                await asyncio.sleep(self.poll_interval)
        raise TimeoutError(f"Flask did not become ready on {url} within {timeout}s")

    async def stop(self) -> None:
        """Terminate the Flask subprocess. SIGTERM, then SIGKILL after 5s.

        Safe to call multiple times; no-op if the process is already
        stopped or never started.
        """
        process = self._process
        if process is None:
            return

        if process.returncode is None:
            try:
                process.terminate()
                await asyncio.wait_for(process.wait(), timeout=5.0)
            except (asyncio.TimeoutError, ProcessLookupError):
                try:
                    process.kill()
                except ProcessLookupError:
                    pass
                await process.wait()

        self._process = None

    def is_up(self) -> bool:
        """True if the subprocess was started and has not exited."""
        return self._process is not None and self._process.returncode is None
