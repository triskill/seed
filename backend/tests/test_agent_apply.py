"""Agent-only settings apply keeps the embedded runtime in place."""
from __future__ import annotations

from fastapi import FastAPI
from fastapi.testclient import TestClient

from seed_backend import service
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS


def test_agent_factory_scopes_the_new_credential_to_pi_children(monkeypatch):
    for name in PI_CREDENTIAL_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    selection = service.AgentApplyRequest(
        provider="openai", modelId="gpt-test", thinkingLevel="low", apiKey="sk-new",
    )

    orchestrator = service._new_orchestrator("http://127.0.0.1:7778", selection)

    assert orchestrator.middleman.cmd[orchestrator.middleman.cmd.index("--model") + 1] == "gpt-test"
    assert orchestrator.worker.cmd[orchestrator.worker.cmd.index("--thinking") + 1] == "low"
    for runner in (orchestrator.middleman, orchestrator.worker):
        assert runner.env is not None
        assert runner.env["OPENAI_API_KEY"] == "sk-new"
        assert not any(
            name != "OPENAI_API_KEY" and name in runner.env
            for name in PI_CREDENTIAL_ENV_VARS
        )


def test_apply_endpoint_replaces_agents_without_returning_api_key(monkeypatch):
    control_app = FastAPI()
    control_app.router.routes.extend(
        route for route in service.app.routes
        if getattr(route, "path", "") == "/control/v1/agents/apply"
    )
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")
    applied = []

    async def replace(app, selection):
        applied.append(selection)

    monkeypatch.setattr(service, "_replace_agents", replace)
    with TestClient(control_app, client=("127.0.0.1", 1234)) as client:
        response = client.post(
            "/control/v1/agents/apply",
            headers={"Authorization": "Bearer capability-test"},
            json={
                "provider": "openai",
                "modelId": "gpt-test",
                "thinkingLevel": "low",
                "apiKey": "sk-secret",
            },
        )

    assert response.status_code == 200
    assert response.json() == {"applied": True}
    assert "sk-secret" not in response.text
    assert len(applied) == 1
    assert applied[0].api_key == "sk-secret"


def test_apply_endpoint_rejects_unknown_provider(monkeypatch):
    control_app = FastAPI()
    control_app.router.routes.extend(
        route for route in service.app.routes
        if getattr(route, "path", "") == "/control/v1/agents/apply"
    )
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")

    with TestClient(control_app, client=("127.0.0.1", 1234)) as client:
        response = client.post(
            "/control/v1/agents/apply",
            headers={"Authorization": "Bearer capability-test"},
            json={
                "provider": "unknown",
                "modelId": "x",
                "thinkingLevel": "off",
                "apiKey": "sk-secret",
            },
        )

    assert response.status_code == 422
    assert "sk-secret" not in response.text
