from __future__ import annotations

import asyncio
import os
import sys
from pathlib import Path

from seed_backend.pi_runner import PiRunner
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS, untrusted_child_env


def test_untrusted_child_env_removes_every_pi_credential_and_capability(monkeypatch):
    monkeypatch.setenv("ANTHROPIC_OAUTH_TOKEN", "oauth-secret")
    monkeypatch.setenv("HF_TOKEN", "hf-secret")
    monkeypatch.setenv("AWS_SECRET_ACCESS_KEY", "aws-secret")
    monkeypatch.setenv("SEED_RUNTIME_CAPABILITY", "capability-secret")
    child = untrusted_child_env()
    assert all(name not in child for name in PI_CREDENTIAL_ENV_VARS)
    assert "SEED_RUNTIME_CAPABILITY" not in child


def test_pi_output_redacts_explicit_runner_environment():
    fixture = Path(__file__).parent / "fixtures" / "fake_pi.py"

    async def scenario():
        runner = PiRunner(
            cmd=[sys.executable, str(fixture)],
            role="worker",
            env={"OPENAI_API_KEY": "seed-secret-xyz"},
        )
        try:
            await runner.start()
            await runner.send("seed-secret-xyz")
            lines = []
            async with asyncio.timeout(5):
                async for line in runner.read_lines():
                    lines.append(line)
                    if line == "done":
                        break
            return lines
        finally:
            await runner.stop()

    lines = asyncio.run(scenario())
    assert "seed-secret-xyz" not in "".join(lines[:-1])
    assert "[REDACTED]" in "".join(lines[:-1])
