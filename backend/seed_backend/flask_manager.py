"""Flask webapp lifecycle manager."""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import httpx

# The x86_64 guest runs under Android QEMU user-mode on physical ARM devices.
# Flask's debug reloader starts a second Python interpreter there, so cold
# imports are substantially slower than host development. Keep FastAPI's
# lifespan from giving up while that child is still becoming ready.
FLASK_STARTUP_TIMEOUT_SECONDS = 90.0


class FlaskManager:
    """Start the generated Flask app separately from FastAPI on port 7778.

    Flask is always a subprocess.  Its development reloader is deliberately
    enabled so worker edits to the generated application become live without
    restarting the FastAPI orchestrator.
    """

    def __init__(
        self,
        port: int = 7778,
        host: str = "127.0.0.1",
        poll_interval: float = 0.2,
        app_dir: str | None = None,
    ) -> None:
        self.port = port
        self.host = host
        self.poll_interval = poll_interval
        self.app_dir = app_dir or self._default_app_dir()
        self._process: asyncio.subprocess.Process | None = None
        self.mode = "stopped"

    @staticmethod
    def _default_app_dir() -> str:
        """Resolve the mutable webapp in embedded and source layouts."""
        configured = os.environ.get("SEED_APP_PATH")
        if configured:
            return os.path.abspath(configured)

        repo_webapp = Path(__file__).resolve().parents[2] / "webapp"
        for candidate in (repo_webapp, Path("/home/seed/app")):
            if candidate.is_dir():
                return str(candidate)
        return "/home/seed/app"

    async def start(self) -> bool:
        """Start Flask's recommended CLI development server with its reloader."""
        if self._process is not None:
            return True

        env = os.environ.copy()
        venv_bin = str(Path(sys.executable).parent)
        env["PATH"] = venv_bin + os.pathsep + env.get("PATH", "")
        try:
            stderr_log = open("/tmp/seed-flask-stderr.log", "w")
            self._process = await asyncio.create_subprocess_exec(
                "flask",
                "--app", "seed_app.app",
                "run",
                "--host", self.host,
                "--port", str(self.port),
                "--debug",
                "--no-debugger",
                stdout=asyncio.subprocess.DEVNULL,
                stderr=stderr_log,
                env=env,
                cwd=self.app_dir,
            )
        except (FileNotFoundError, OSError, PermissionError) as exc:
            self._process = None
            self.mode = "failed"
            with open("/tmp/seed-flask-stderr.log", "w") as fh:
                fh.write(f"Flask subprocess spawn failed: {exc!r}\n")
            return False

        try:
            await self.wait_ready(timeout=FLASK_STARTUP_TIMEOUT_SECONDS)
        except (TimeoutError, OSError):
            await self.stop()
            self.mode = "failed"
            return False

        self.mode = "subprocess"
        return True

    async def wait_ready(self, timeout: float = 10.0) -> None:
        """Poll ``/api/ping`` until Flask responds or the timeout expires."""
        url = f"http://{self.host}:{self.port}/api/ping"
        loop = asyncio.get_event_loop()
        deadline = loop.time() + timeout
        async with httpx.AsyncClient() as client:
            while loop.time() < deadline:
                try:
                    response = await client.get(url, timeout=1.0)
                    if response.status_code == 200:
                        return
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(self.poll_interval)
        raise TimeoutError(f"Flask did not become ready on {url} within {timeout}s")

    async def stop(self) -> None:
        """Terminate the Flask process, escalating to SIGKILL after five seconds."""
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
        if self.mode == "subprocess":
            self.mode = "stopped"

    def is_up(self) -> bool:
        """Whether the separate Flask process is still running."""
        return self._process is not None and self._process.returncode is None
