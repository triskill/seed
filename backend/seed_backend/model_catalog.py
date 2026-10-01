"""Offline-safe wiring for Pi's explicit authenticated model-catalog update."""
from __future__ import annotations

import asyncio
import os
import signal
import subprocess
import threading
import time

from seed_backend.orchestrator import pi_cmd_for_role, pi_env_for_role
from seed_backend.pi_control import PiControlError
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS

# Pi has a cooperative 15-second timeout. Leave room for forced cleanup below
# Android's 30-second HTTP read deadline; callers cannot increase this bound.
UPDATE_TIMEOUT_SECONDS = 24.0


async def update_models(*, timeout: float = UPDATE_TIMEOUT_SECONDS) -> None:
    """Run fixed argv in a PRoot-compatible thread, owning it through reap.

    All diagnostics go to DEVNULL, not logs, memory buffers, or API responses.
    The worker owns spawn + termination so cancellation even during Popen cannot
    orphan a child. Only this trusted Pi operation restores provider env keys;
    shell and Flask retain their existing credential-free environment boundary.
    """
    if not 0 < timeout <= UPDATE_TIMEOUT_SECONDS:
        raise ValueError('invalid model update timeout')
    command = [pi_cmd_for_role('control')[0], 'update', '--models']
    environment = pi_env_for_role('control')
    for name in PI_CREDENTIAL_ENV_VARS:
        value = os.environ.get(name)
        if value is not None:
            environment[name] = value
    cancelled = threading.Event()

    def run() -> None:
        process = None
        deadline = time.monotonic() + timeout
        try:
            process = subprocess.Popen(
                command, env=environment,
                stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, start_new_session=True, close_fds=True,
            )
            while not cancelled.is_set():
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise PiControlError('model catalog update timed out')
                try:
                    code = process.wait(timeout=min(0.05, remaining))
                except subprocess.TimeoutExpired:
                    continue
                if code != 0:
                    raise PiControlError('model catalog update failed')
                return
        except PiControlError:
            raise
        except Exception:
            raise PiControlError('model catalog update failed') from None
        finally:
            if process is not None:
                # On timeout/cancellation the leader is still owned/unreaped.
                # Never signal after wait() reaped it: its PGID could be reused.
                if process.returncode is None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except OSError:
                        process.kill()  # PRoot may reject group signaling
                process.wait()

    worker = asyncio.create_task(asyncio.to_thread(run))
    try:
        await asyncio.shield(worker)
    except asyncio.CancelledError:
        cancelled.set()
        # Repeated client cancellations must not release the control lock until
        # the spawn-owning thread has collected and reaped its child.
        while not worker.done():
            try:
                await asyncio.shield(worker)
            except asyncio.CancelledError:
                continue
            except Exception:
                break
        if not worker.cancelled():
            worker.exception()  # consume any failure without exposing it
        raise
