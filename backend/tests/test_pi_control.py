from __future__ import annotations

import asyncio

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from seed_backend.pi_control import PiControlService
from seed_backend.service import app


class FakeRunner:
    async def start(self):
        return None

    async def stop(self):
        return None

    async def rpc_request(self, payload, *, timeout):
        command = payload["type"]
        if command == "get_available_models":
            return {
                "type": "response", "success": True, "data": {"models": [{
                    "provider": "openai", "id": "gpt-test", "name": "Test",
                    "reasoning": True, "input": ["text"], "contextWindow": 100,
                    "maxTokens": 10, "headers": {"Authorization": "sk-secret"},
                }]},
            }
        if command == "set_model":
            return {
                "type": "response", "success": True,
                "data": {"provider": "openai", "id": "gpt-test", "name": "Test", "reasoning": True},
            }
        return {"type": "response", "success": True}


def service() -> PiControlService:
    return PiControlService(runner=FakeRunner())


def test_catalog_sanitizes_pi_metadata_and_derives_thinking_levels():
    result = asyncio.run(service().get_available_models())
    model = result["models"][0]
    assert model["id"] == "gpt-test"
    assert model["thinkingLevels"] == ["off", "minimal", "low", "medium", "high"]
    assert "headers" not in model
    assert "sk-secret" not in str(result)
    assert PiControlService._thinking_levels({"reasoning": True, "thinkingLevelMap": {"off": None}}) == ["minimal", "low", "medium", "high"]


def test_selection_validates_tuple_before_mutation():
    svc = service()
    with pytest.raises(Exception):
        asyncio.run(svc.validate_selection("openai", "missing", "low"))


def test_control_endpoint_requires_capability_and_returns_no_secret(monkeypatch):
    control_app = FastAPI()
    control_routes = [route for route in app.routes if getattr(route, "path", "").startswith("/control/v1/")]
    control_app.router.routes.extend(control_routes)
    control_app.state.control_service = service()
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")
    with TestClient(control_app, client=("127.0.0.1", 1234)) as client:
        assert client.get("/control/v1/models").status_code == 401
        response = client.get("/control/v1/models", headers={"Authorization": "Bearer capability-test"})
    assert response.status_code == 200
    assert "sk-secret" not in response.text
    assert response.json()["models"][0]["id"] == "gpt-test"


class FlakyRunner(FakeRunner):
    def __init__(self, state):
        self.state = state

    async def rpc_request(self, payload, *, timeout):
        if self.state["fail"]:
            self.state["fail"] = False
            raise RuntimeError("provider secret=sk-do-not-return")
        return await super().rpc_request(payload, timeout=timeout)


def test_control_runner_is_recreated_after_rpc_failure():
    state = {"fail": True, "created": 0}

    def factory():
        state["created"] += 1
        return FlakyRunner(state)

    async def run():
        svc = PiControlService(runner_factory=factory)
        with pytest.raises(Exception):
            await svc.get_available_models()
        result = await svc.get_available_models()
        await svc.stop()
        return result

    result = asyncio.run(run())
    assert state["created"] == 2
    assert result["models"][0]["id"] == "gpt-test"


class ErrorControl:
    async def get_available_models(self):
        from seed_backend.pi_control import PiControlError
        raise PiControlError("provider response Authorization: sk-secret")


def test_control_endpoint_genericizes_provider_failures(monkeypatch):
    control_app = FastAPI()
    control_routes = [route for route in app.routes if getattr(route, "path", "").startswith("/control/v1/")]
    control_app.router.routes.extend(control_routes)
    control_app.state.control_service = ErrorControl()
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-test")
    with TestClient(control_app, client=("127.0.0.1", 1234)) as client:
        response = client.get("/control/v1/models", headers={"Authorization": "Bearer capability-test"})
    assert response.status_code == 502
    assert response.json()["detail"] == "Pi control request failed"
    assert "sk-secret" not in response.text
