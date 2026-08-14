"""Config loading from config.json.

Task 0.3: small, self-contained config dataclass with JSON I/O. Holds
the LLM provider, model name, API key, and the ports the backend and
Flask webapp bind to. Used by later tasks (Flask manager, pi runner,
settings sync from Android).

Validation is intentionally minimal — the dataclass field types are the
contract. Heavier validation (env overrides, schema files, etc.) is
deferred to a later task if it's ever needed.
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


# Module-level so load() can fall back to the documented defaults
# without re-instantiating a Config (and the ports dict is mutable).
DEFAULT_PORTS: dict[str, int] = {"backend": 7777, "flask": 7778}


@dataclass
class Config:
    """Seed backend configuration.

    Attributes:
        provider: LLM provider name (e.g., "anthropic", "openai").
        model:    Model identifier (e.g., "claude-sonnet-4").
        api_key:  Legacy host-side API-key field. Android deliberately never
                  writes its encrypted credential here; embedded credentials
                  enter through provider-specific process environment vars.
        ports:    Mapping of service name -> TCP port. Currently
                  "backend" (FastAPI) and "flask" (webapp).
    """

    provider: str = "anthropic"
    model: str = "claude-sonnet-4"
    api_key: str = ""
    ports: dict[str, int] = field(default_factory=lambda: dict(DEFAULT_PORTS))

    @classmethod
    def load(cls, path: Path) -> "Config":
        """Load a Config from a JSON file.

        Missing fields fall back to the dataclass defaults. Raises
        FileNotFoundError if the path does not exist, and json.JSONDecodeError
        if the file is not valid JSON.
        """
        with open(path) as f:
            data = json.load(f)
        return cls(
            provider=data.get("provider", "anthropic"),
            model=data.get("model", "claude-sonnet-4"),
            api_key=data.get("api_key", ""),
            ports=data.get("ports", dict(DEFAULT_PORTS)),
        )

    def save(self, path: Path) -> None:
        """Write this Config to a JSON file at `path` (overwrites)."""
        path.write_text(
            json.dumps(
                {
                    "provider": self.provider,
                    "model": self.model,
                    "api_key": self.api_key,
                    "ports": self.ports,
                },
                indent=2,
            )
            + "\n"
        )
