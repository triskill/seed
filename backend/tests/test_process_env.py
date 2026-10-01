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


def test_pi_0842_credentials_do_not_leak_to_shell_flask_or_pi(monkeypatch, tmp_path):
    from seed_backend import flask_manager
    from seed_backend.orchestrator import pi_env_for_role
    from seed_backend.shell import exec_command

    names = ("RADIUS_API_KEY", "BASETEN_API_KEY", "QWEN_TOKEN_PLAN_CN_API_KEY")
    for name in names:
        monkeypatch.setenv(name, f"private-{name}")
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path / 'agent'))
    child = untrusted_child_env()
    assert all(name not in child for name in names)
    for role in ('worker', 'middleman', 'control'):
        assert all(name not in pi_env_for_role(role) for name in names)
    result = asyncio.run(exec_command('env'))
    assert all(f"private-{name}" not in result.stdout for name in names)

    seen = {}
    def spawn(*args, **kwargs):
        seen.update(kwargs)
        return object()
    async def ready(*args, **kwargs):
        pass
    monkeypatch.setattr(flask_manager.subprocess, 'Popen', spawn)
    manager = flask_manager.FlaskManager(app_dir=str(tmp_path))
    monkeypatch.setattr(manager, 'wait_ready', ready)
    assert asyncio.run(manager.start())
    assert all(name not in seen['env'] for name in names)


def test_terminal_and_pi_share_runtime_config_directory(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path / 'agent'))
    from seed_backend.orchestrator import pi_env_for_role
    assert untrusted_child_env()['PI_CODING_AGENT_DIR'] == pi_env_for_role('worker')['PI_CODING_AGENT_DIR']
    monkeypatch.delenv('PI_CODING_AGENT_DIR')
    assert untrusted_child_env()['PI_CODING_AGENT_DIR'] == pi_env_for_role('worker')['PI_CODING_AGENT_DIR']


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
