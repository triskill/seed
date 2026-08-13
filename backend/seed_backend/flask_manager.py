"""Flask webapp lifecycle manager.

Task 0.5 shipped the subprocess mode: spawn `flask --app seed_app.app
run` as a child process, poll `/api/ping` for readiness, terminate
cleanly on shutdown. This still works for dev (`./backend/scripts/dev.sh`)
where the worker agent edits `app.py` and the Werkzeug reloader
(enabled by `FLASK_DEBUG=1`) picks up the changes on the next request.

Phase 8 carry-over added the embedded-runtime WSGI mount: the
Android app extracts an Alpine rootfs and launches the orchestrator
through proot. proot on Android does not implement `fork(2)`
(returns `ENOSYS = errno 38`), so the Flask subprocess can't be
spawned from inside proot. As a fallback, the lifespan mounts the
Flask WSGI app directly inside the FastAPI process via `a2wsgi` —
no subprocess, no reloader, but the webapp routes (`/`, `/api/ping`,
and any worker-added routes) still serve on `/`.

The `start()` method returns `True` when subprocess mode succeeded
and `False` when the caller should switch to WSGI mount mode. The
service lifespan tries subprocess first, then falls back.
"""
from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

import httpx


class FlaskManager:
    """Lifecycle wrapper around the Flask webapp.

    Two modes:
      * subprocess mode (default for dev): spawn `flask --app
        seed_app.app run`, poll /api/ping. The Flask reloader
        (FLASK_DEBUG=1) picks up worker edits to app.py.
      * WSGI mount mode (fallback for embedded runtime): the
        Flask app's `wsgi_app` is mounted inside the FastAPI
        process via `a2wsgi.WSGIMiddleware`. No subprocess, no
        reloader, no `flask` binary on PATH — but the same routes
        are served.

    Attributes:
        port:           TCP port Flask binds to (subprocess mode
                        only; ignored in WSGI mount mode where
                        the FastAPI process owns the port).
        host:           Same as `port`.
        poll_interval:  Seconds between `/api/ping` polls while
                        waiting for readiness (subprocess mode).
        app_dir:        CWD passed to the Flask subprocess so
                        `flask --app seed_app.app` resolves the
                        import.
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
        # Tracks which mode is active (set by start() or
        # mount_wsgi()). The /health endpoint reads this.
        self.mode: str = "stopped"

    @staticmethod
    def _default_app_dir() -> str:
        """Resolve the mutable webapp in embedded and source layouts.

        `SEED_APP_PATH` is also passed to both agent roles, so honoring it here
        keeps Flask and the worker pointed at the same tree. Without an
        override, locate the repository webapp relative to this module rather
        than the caller's working directory, then fall back to the embedded
        runtime path.
        """
        configured = os.environ.get("SEED_APP_PATH")
        if configured:
            return os.path.abspath(configured)

        repo_webapp = Path(__file__).resolve().parents[2] / "webapp"
        candidates = (repo_webapp, Path("/home/seed/app"))
        for candidate in candidates:
            if candidate.is_dir():
                return str(candidate)
        return "/home/seed/app"

    async def start(self) -> bool:
        """Try to spawn the Flask webapp as a subprocess.

        Returns `True` if the subprocess started and `/api/ping`
        came up within the timeout. Returns `False` if the
        subprocess failed to spawn (e.g. ENOSYS from fork() in
        proot, or no `flask` binary on PATH) — the caller should
        fall back to `mount_wsgi()`.

        The venv's `flask` binary is located by prepending the
        directory of the current Python interpreter to PATH, so
        the manager works whether the venv was activated by the
        caller or not.
        """
        if self._process is not None:
            return True

        env = os.environ.copy()
        venv_bin = str(Path(sys.executable).parent)
        env["PATH"] = venv_bin + os.pathsep + env.get("PATH", "")
        # Worker edits to app.py need the reloader to pick up
        # changes — see the module docstring for the trade-off.
        env["FLASK_DEBUG"] = "1"

        try:
            # Capture stderr to a /tmp file so the lifespan can
            # surface startup failures in /health diagnostics.
            stderr_log = open("/tmp/seed-flask-stderr.log", "w")
            self._process = await asyncio.create_subprocess_exec(
                "flask",
                "--app", "seed_app.app",
                "run",
                "--host", self.host,
                "--port", str(self.port),
                stdout=asyncio.subprocess.DEVNULL,
                stderr=stderr_log,
                env=env,
                cwd=self.app_dir,
            )
        except (FileNotFoundError, OSError, PermissionError) as exc:
            # No `flask` on PATH, or fork() failed inside proot.
            # Caller will fall back to WSGI mount mode. Write
            # the exception to the stderr log so the lifespan
            # can include it in /health diagnostics.
            self._process = None
            self.mode = "subprocess_failed"
            with open("/tmp/seed-flask-stderr.log", "w") as fh:
                fh.write(f"subprocess spawn failed: {exc!r}\n")
            return False

        # Polling /api/ping has the same failure modes as
        # the spawn above — a proot-failed child won't bind
        # the port and we'll just time out here. Caller decides
        # whether to fall back.
        try:
            await self.wait_ready(timeout=15)
        except (TimeoutError, OSError):
            await self.stop()
            self.mode = "subprocess_failed"
            return False

        self.mode = "subprocess"
        return True

    async def wait_ready(self, timeout: float = 10.0) -> None:
        """Poll `/api/ping` until it returns 200 or the timeout elapses."""
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
        """Terminate the Flask subprocess. No-op in WSGI mount mode.

        SIGTERM, then SIGKILL after 5s. Safe to call multiple
        times; no-op if the process is already stopped or never
        started in subprocess mode.
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
        """True when the Flask routes are reachable on the port (or WSGI-mounted)."""
        if self.mode == "wsgi_mount":
            return True
        return self._process is not None and self._process.returncode is None

    def mount_wsgi(self) -> None:
        """Mark the manager as running in WSGI mount mode.

        Called by the service lifespan when subprocess mode failed
        and after the Flask WSGI app has been mounted inside the
        FastAPI process. This class itself doesn't perform the
        mount — `service.py` owns the FastAPI app and imports
        the Flask app via a2wsgi. We just record the mode so
        `is_up()` returns True and `/health` reports it.
        """
        self.mode = "wsgi_mount"
        self._process = None
