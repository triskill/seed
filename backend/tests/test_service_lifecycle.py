"""Tests for the FastAPI service lifespan + orchestrator (Task 3.1).

The lifespan is responsible for bringing up the two `pi` agent
processes (middle-man and worker) before the orchestrator starts
accepting requests, and for tearing them down cleanly on shutdown.
This test pins down the contract: after the lifespan runs, an
Orchestrator is reachable via `app.state.orchestrator`, it owns two
live `PiRunner` instances with distinct PIDs, and a shutdown of the
lifespan terminates both children.

The default production `pi` cmd is monkey-patched to the local
`fake_pi.py` fixture so the test runs without a real `pi` install
or an LLM. The fake pi is a Python script that blocks on stdin
until a line is written, then emits three progress events and a
`done` marker before exiting. As long as we don't send it any
input it stays alive across the assertion window, which is what
the test relies on.
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.service import app


def _fake_pi_cmd() -> list[str]:
    """Argv that points at the in-tree fake pi fixture."""
    return [sys.executable, str(Path(__file__).parent / "fixtures" / "fake_pi.py")]


@pytest.fixture
def orchestrator_client(monkeypatch):
    """TestClient wired to a lifespan that spawns fake pi for both roles.

    Yields the TestClient; the `with` block triggers the lifespan on
    entry and tears it down on exit. Yields a fully-initialized
    orchestrator reachable via `client.app.state.orchestrator`.
    """
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: _fake_pi_cmd())
    with TestClient(app) as client:
        yield client


def test_lifespan_creates_orchestrator_with_two_runners(orchestrator_client):
    """app.state.orchestrator has middleman + worker PiRunners with valid PIDs.

    Two distinct PIDs (and a non-None orchestrator) prove the lifespan
    actually launched the fake pi twice — one per role. If either
    runner hadn't been started, its `pid` would be None.
    """
    orch = orchestrator_client.app.state.orchestrator
    assert orch is not None
    assert orch.middleman.pid is not None and orch.middleman.pid > 0
    assert orch.worker.pid is not None and orch.worker.pid > 0
    # Two distinct child processes.
    assert orch.middleman.pid != orch.worker.pid


def test_lifespan_orchestrator_middleman_and_worker_have_roles(orchestrator_client):
    """The two runners are tagged with the right roles.

    Phase 3.4 uses the role to decide which tool calls to allow
    (middle-man is read-only); pinning the role here makes sure
    the lifespan wires the right role to the right runner.
    """
    orch = orchestrator_client.app.state.orchestrator
    assert orch.middleman.role == "middleman"
    assert orch.worker.role == "worker"


def test_lifespan_enforces_middleman_read_only_tools(orchestrator_client):
    """Production wiring enables the event-filter backstop for one role only."""
    orch = orchestrator_client.app.state.orchestrator
    assert orch.middleman.read_only_tools == {"read", "grep", "find", "ls"}
    assert orch.worker.read_only_tools is None
    assert orch.middleman.env is not None
    assert orch.worker.env is not None
    assert orch.middleman.env["SEED_APP_URL"] == "http://127.0.0.1:7778"
    assert orch.worker.env["SEED_APP_URL"] == "http://127.0.0.1:7778"


def test_lifespan_stops_runners_on_shutdown(monkeypatch):
    """Lifespan shutdown terminates both PiRunners (pid reset to None)."""
    monkeypatch.setattr(service, "pi_cmd_for_role", lambda role: _fake_pi_cmd())
    orch_ref: list = []
    with TestClient(app) as client:
        orch_ref.append(client.app.state.orchestrator)
        # Sanity: both runners are alive during the lifespan.
        assert orch_ref[0].middleman.pid is not None
        assert orch_ref[0].worker.pid is not None
    # After the lifespan exits, both runners should have been
    # stopped by Orchestrator.stop() and have their pids cleared.
    assert orch_ref[0].middleman.pid is None
    assert orch_ref[0].worker.pid is None


@pytest.mark.parametrize('failed_role', ['middleman', 'worker'])
def test_lifespan_agent_failure_aborts_and_cleans_up(monkeypatch, failed_role):
    """Even a worker-only failure must abort startup and release Flask and Pi."""
    from seed_backend.orchestrator import Orchestrator
    from seed_backend.pi_runner import PiRunner

    managers = []
    agents = []
    controls = []

    class Control:
        async def stop(self):
            self.stopped = True

    def make_control(url):
        control = Control()
        control.stopped = False
        controls.append(control)
        return control

    class Manager:
        def __init__(self, **kwargs):
            self.stopped = False
            managers.append(self)

        async def start(self):
            return True

        async def stop(self):
            self.stopped = True

    def make_agents(url):
        good = _fake_pi_cmd()
        bad = ['/nonexistent/seed-pi-executable']
        orch = Orchestrator(
            PiRunner(bad if failed_role == 'middleman' else good, role='middleman'),
            PiRunner(bad if failed_role == 'worker' else good, role='worker'),
        )
        agents.append(orch)
        return orch

    monkeypatch.setattr(service, 'FlaskManager', Manager)
    monkeypatch.setattr(service, '_new_control_service', make_control)
    monkeypatch.setattr(service, '_new_orchestrator', make_agents)
    isolated_app = service.FastAPI(lifespan=service.lifespan)
    with pytest.raises(FileNotFoundError):
        with TestClient(isolated_app):
            pass
    assert managers[0].stopped
    assert controls[0].stopped
    assert isolated_app.state.orchestrator is None
    assert not agents[0].ready
    assert agents[0].middleman.pid is agents[0].worker.pid is None


def test_lifespan_shutdown_waits_for_inflight_apply_and_stops_replacement(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    starting = asyncio.Event()
    release = asyncio.Event()
    agents = []

    class Manager:
        def __init__(self, **kwargs): pass
        async def start(self): return True
        async def stop(self): pass

    class Agents:
        def __init__(self, gated=False):
            self.gated = gated
            self.ready = False
            self.task_status = None
            self._active = False
            self._subscribers = set()
            self._acceptances = {}
            self._acceptance_lock = asyncio.Lock()
            self.stops = 0
            agents.append(self)

        async def start(self):
            if self.gated:
                starting.set()
                await release.wait()
            self.ready = True

        async def stop(self):
            self.ready = False
            self.stops += 1

    monkeypatch.setattr(service, 'FlaskManager', Manager)
    monkeypatch.setattr(service, '_new_control_service', lambda url: type('Control', (), {'stop': lambda self: asyncio.sleep(0)})())
    monkeypatch.setattr(service, '_new_orchestrator', lambda url, selection=None: Agents(selection is not None))

    async def scenario():
        isolated = service.FastAPI(lifespan=service.lifespan)
        context = service.lifespan(isolated)
        await context.__aenter__()
        applying = asyncio.create_task(service._replace_agents(
            isolated, service.AgentApplyRequest(provider='openai', modelId='new')))
        await asyncio.wait_for(starting.wait(), 2)
        shutting_down = asyncio.create_task(context.__aexit__(None, None, None))
        await asyncio.sleep(0)
        assert not shutting_down.done()
        release.set()
        await applying
        await shutting_down
        assert isolated.state.orchestrator is None
        assert len(agents) == 2
        assert all(not agent.ready and agent.stops >= 1 for agent in agents)
        with pytest.raises(RuntimeError, match='shutting down'):
            await service._replace_agents(isolated, service.AgentApplyRequest(provider='openai', modelId='another'))
        assert len(agents) == 2
        assert service.pi_settings.read_json('settings.json')['defaultModel'] == 'new'

    asyncio.run(scenario())


def test_real_runner_exit_makes_health_unavailable_and_rejects_chat(monkeypatch):
    import os
    import signal
    import time
    from starlette.websockets import WebSocketDisconnect

    class Manager:
        def __init__(self, **kwargs): pass
        async def start(self): return True
        async def stop(self): pass
        def is_up(self): return True

    monkeypatch.setattr(service, 'FlaskManager', Manager)
    monkeypatch.setattr(service, 'pi_cmd_for_role', lambda role: _fake_pi_cmd())
    isolated_app = service.FastAPI(lifespan=service.lifespan)
    isolated_app.add_api_route('/health', service.health, methods=['GET'])
    isolated_app.add_api_websocket_route('/chat', service.chat_endpoint)
    with TestClient(isolated_app) as client:
        agents = isolated_app.state.orchestrator
        assert client.get('/health').status_code == 200
        os.kill(agents.middleman.pid, signal.SIGTERM)
        deadline = time.monotonic() + 5
        while agents.middleman.ready and time.monotonic() < deadline:
            time.sleep(.01)
        assert not agents.middleman.ready
        assert client.get('/health').status_code == 503
        with pytest.raises(WebSocketDisconnect) as caught:
            with client.websocket_connect('/chat') as ws:
                ws.receive_text()
        assert caught.value.code == 1011


def test_health_and_chat_reject_unready_agents(monkeypatch):
    from starlette.websockets import WebSocketDisconnect

    isolated_app = service.FastAPI()
    isolated_app.add_api_route('/health', service.health, methods=['GET'])
    isolated_app.add_api_websocket_route('/chat', service.chat_endpoint)

    class Manager:
        def is_up(self):
            return True

    class Agents:
        ready = False

    isolated_app.state.flask_manager = Manager()
    isolated_app.state.orchestrator = Agents()
    with TestClient(isolated_app) as client:
        response = client.get('/health')
        assert response.status_code == 503
        assert response.json()['flask'] == 'up'
        with pytest.raises(WebSocketDisconnect) as caught:
            with client.websocket_connect('/chat') as ws:
                ws.receive_text()
        assert caught.value.code == 1011

@pytest.mark.parametrize('raises', [False, True])
def test_lifespan_fails_when_generated_flask_cannot_start(monkeypatch, raises):
    """Failed Flask startup releases a partially spawned process too."""
    stopped = []

    async def failed_start(self):
        if raises:
            raise OSError('partial launch')
        return False

    async def stop(self):
        stopped.append(True)

    monkeypatch.setattr(service.FlaskManager, "start", failed_start)
    monkeypatch.setattr(service.FlaskManager, "stop", stop)
    isolated_app = service.FastAPI(lifespan=service.lifespan)

    with pytest.raises(RuntimeError, match="Flask failed to start"):
        with TestClient(isolated_app):
            pass
    assert stopped == [True]
    assert isolated_app.state.orchestrator is None
