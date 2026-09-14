"""Headless, allowlisted model-control RPC for the bundled Pi process."""
from __future__ import annotations

import asyncio
from typing import Any, Callable

from seed_backend.pi_runner import PiRunner


THINKING_LEVELS = ("off", "minimal", "low", "medium", "high", "xhigh")


class PiControlError(RuntimeError):
    """A model-control operation failed without exposing credentials."""


class PiControlService:
    """Own a dedicated Pi RPC process for catalog and selection operations.

    The process is deliberately separate from chat's middleman and worker. It
    has no tools, prompts, extensions, or session persistence. ``runner_factory``
    is injected by the FastAPI lifespan and makes this class easy to fixture.
    """

    _ALLOWED = frozenset({
        "get_available_models",
        "set_model",
        "set_thinking_level",
    })

    def __init__(
        self,
        runner: PiRunner | None = None,
        *,
        runner_factory: Callable[[], PiRunner] | None = None,
        timeout: float = 30.0,
    ) -> None:
        self.runner = runner
        self.runner_factory = runner_factory
        self.timeout = timeout
        self._start_lock = asyncio.Lock()
        self._call_lock = asyncio.Lock()
        self._started = False

    async def start(self) -> None:
        async with self._start_lock:
            if self._started:
                return
            if self.runner is None:
                if self.runner_factory is None:
                    raise PiControlError("Pi control runner is not configured")
                self.runner = self.runner_factory()
            try:
                await self.runner.start()
            except Exception as exc:
                runner = self.runner
                self.runner = None
                try:
                    await runner.stop()
                except Exception:
                    pass
                raise PiControlError("Pi control process unavailable") from exc
            self._started = True

    async def _reset_runner(self) -> None:
        # Reset under the same lock used by start(). A failed/EOF'd Pi process
        # must not leave a dead runner cached for every later catalog request.
        async with self._start_lock:
            runner = self.runner
            self.runner = None
            self._started = False
        if runner is not None:
            try:
                await runner.stop()
            except Exception:
                pass

    async def stop(self) -> None:
        async with self._call_lock:
            await self._reset_runner()

    async def _call(self, command: str, **params: Any) -> dict[str, Any]:
        if command not in self._ALLOWED:
            raise PiControlError("unsupported Pi control command")
        # Selection is intentionally serialized with catalog retrieval. It
        # also makes failure reset atomic with respect to the next request.
        async with self._call_lock:
            await self.start()
            assert self.runner is not None
            runner = self.runner
            payload = {"type": command, **params}
            try:
                response = await runner.rpc_request(payload, timeout=self.timeout)
            except asyncio.CancelledError:
                raise
            except asyncio.TimeoutError as exc:
                await self._reset_runner()
                raise PiControlError("Pi control request timed out") from exc
            except Exception as exc:
                # Pi/provider diagnostics can echo request details. Keep them
                # in neither HTTP responses nor logs; Android only needs retry.
                await self._reset_runner()
                raise PiControlError("Pi control request failed") from exc
            if not isinstance(response, dict):
                await self._reset_runner()
                raise PiControlError("invalid Pi control response")
            if response.get("success") is not True:
                # The process may remain alive on a normal Pi error, but reset
                # anyway: this avoids retaining a poisoned control session.
                await self._reset_runner()
                raise PiControlError("Pi control request failed")
            return response

    @staticmethod
    def _thinking_levels(model: dict[str, Any]) -> list[str]:
        if not model.get("reasoning"):
            return ["off"]
        level_map = model.get("thinkingLevelMap")
        if not isinstance(level_map, dict):
            level_map = {}
        # Pi 0.80.3's RPC exposes set_thinking_level but not a query command.
        # Mirror its getSupportedThinkingLevels implementation from the model
        # metadata returned by get_available_models.
        return [
            level for level in THINKING_LEVELS
            if level != "xhigh" and level_map.get(level, "__default__") is not None
        ] + (["xhigh"] if level_map.get("xhigh") is not None else [])

    @staticmethod
    def _public_model(model: Any) -> dict[str, Any]:
        if not isinstance(model, dict):
            raise PiControlError("Pi returned an invalid model")
        provider = model.get("provider")
        model_id = model.get("id")
        name = model.get("name")
        if not all(isinstance(value, str) and value for value in (provider, model_id, name)):
            raise PiControlError("Pi returned an incomplete model")
        input_types = model.get("input")
        if not isinstance(input_types, list):
            input_types = []
        return {
            "provider": provider,
            "id": model_id,
            "name": name,
            "contextWindow": model.get("contextWindow") if isinstance(model.get("contextWindow"), int) else None,
            "maxTokens": model.get("maxTokens") if isinstance(model.get("maxTokens"), int) else None,
            "input": [value for value in input_types if isinstance(value, str)],
            "supportsThinking": bool(model.get("reasoning")),
            "thinkingLevels": PiControlService._thinking_levels(model),
        }

    async def get_available_models(self) -> dict[str, Any]:
        response = await self._call("get_available_models")
        data = response.get("data")
        models = data.get("models") if isinstance(data, dict) else None
        if not isinstance(models, list):
            raise PiControlError("Pi returned no model catalog")
        public = [self._public_model(model) for model in models]
        return {"models": public}

    async def get_available_thinking_levels(self, provider: str, model_id: str) -> dict[str, Any]:
        catalog = await self.get_available_models()
        model = next((m for m in catalog["models"] if m["provider"] == provider and m["id"] == model_id), None)
        if model is None:
            raise PiControlError("model is not in the Pi catalog")
        return {"provider": provider, "modelId": model_id, "levels": model["thinkingLevels"]}

    async def validate_selection(
        self,
        provider: str,
        model_id: str,
        thinking_level: str | None = None,
    ) -> dict[str, Any]:
        # Check the exact tuple against Pi's current authenticated catalog before
        # mutating the control session. This keeps invalid UI input side-effect free.
        catalog = await self.get_available_models()
        public = next(
            (item for item in catalog["models"]
             if item["provider"] == provider and item["id"] == model_id),
            None,
        )
        if public is None:
            raise PiControlError("model selection is not in the Pi catalog")
        if thinking_level is not None and thinking_level not in public["thinkingLevels"]:
            raise PiControlError("thinking level is not supported by this model")
        response = await self._call("set_model", provider=provider, modelId=model_id)
        selected = response.get("data")
        # Pi returns the selected model as data; sanitize it before crossing the API.
        public = self._public_model(selected)
        if thinking_level is not None:
            await self._call("set_thinking_level", level=thinking_level)
        return {
            "valid": True,
            "model": public,
            "thinkingLevel": thinking_level or "off",
        }
