"""Offline model-update lifecycle tests: never invoke the real Pi CLI."""
import asyncio
import signal
import subprocess
import sys
import threading
import time

import pytest

from seed_backend import model_catalog
from seed_backend.pi_control import PiControlError
from seed_backend.process_env import PI_CREDENTIAL_ENV_VARS


@pytest.fixture(autouse=True)
def isolated_credentials(monkeypatch, tmp_path):
    # Delete known names without reading host credential values. Never let a
    # fake subprocess inherit the developer's auth directory or credentials.
    for name in PI_CREDENTIAL_ENV_VARS:
        monkeypatch.delenv(name, raising=False)
    monkeypatch.setenv('HOME', str(tmp_path))
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path / 'agent'))


class Process:
    pid = 12345

    def __init__(self, code=0):
        self.code = code
        self.returncode = None
        self.reaped = False
        self.killed = False

    def poll(self):
        return self.code

    def wait(self, timeout=None):
        if self.code is None:
            time.sleep(min(timeout or 0.01, 0.01))
            raise subprocess.TimeoutExpired('private diagnostic', timeout)
        self.reaped = True
        self.returncode = self.code
        return self.code

    def kill(self):
        self.killed = True
        self.code = -9


@pytest.mark.parametrize('code', [0, 1])
def test_fixed_command_shared_directory_credentials_and_suppressed_output(monkeypatch, tmp_path, capsys, code):
    process = Process(code)
    seen = {}
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setenv('SEED_RUNTIME_CAPABILITY', 'test-capability')
    monkeypatch.setenv('OPENAI_API_KEY', 'fake-openai')
    monkeypatch.setenv('ANTHROPIC_API_KEY', 'fake-anthropic')
    new_credentials = ('RADIUS_API_KEY', 'BASETEN_API_KEY', 'QWEN_TOKEN_PLAN_CN_API_KEY')
    for name in new_credentials:
        monkeypatch.setenv(name, f'fake-{name}')
    # The trusted updater must explicitly restore credentials after the
    # shared Pi boundary strips them; agent/control children never get them.
    stripped = model_catalog.pi_env_for_role('control')
    assert all(name not in stripped for name in new_credentials)
    monkeypatch.setattr(model_catalog, 'pi_env_for_role', lambda role: stripped.copy())
    monkeypatch.setattr(model_catalog, 'pi_cmd_for_role', lambda role: ['/fixed/pi', '--mode', 'rpc'])

    def popen(cmd, **kwargs):
        seen.update(cmd=cmd, **kwargs)
        return process

    monkeypatch.setattr(model_catalog.subprocess, 'Popen', popen)
    monkeypatch.setattr(model_catalog.os, 'killpg', lambda *args: None)
    if code:
        with pytest.raises(PiControlError, match='model catalog update failed'):
            asyncio.run(model_catalog.update_models())
    else:
        asyncio.run(model_catalog.update_models())
    assert seen['cmd'] == ['/fixed/pi', 'update', '--models']
    assert seen['env']['PI_CODING_AGENT_DIR'] == str(tmp_path)
    assert seen['env']['OPENAI_API_KEY'] == 'fake-openai'
    assert seen['env']['ANTHROPIC_API_KEY'] == 'fake-anthropic'
    assert all(seen['env'][name] == f'fake-{name}' for name in new_credentials)
    assert 'SEED_RUNTIME_CAPABILITY' not in seen['env']
    assert all(seen[key] == subprocess.DEVNULL for key in ('stdin', 'stdout', 'stderr'))
    assert seen['start_new_session'] and seen['close_fds']
    assert process.reaped
    assert capsys.readouterr() == ('', '')


def test_timeout_kills_group_and_reaps_with_proot_fallback(monkeypatch, tmp_path):
    process = Process(None)
    signals = []
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    monkeypatch.setattr(model_catalog.subprocess, 'Popen', lambda *a, **kw: process)

    def killpg(pid, sig):
        signals.append((pid, sig))
        raise PermissionError('PRoot group unavailable')

    monkeypatch.setattr(model_catalog.os, 'killpg', killpg)
    with pytest.raises(PiControlError, match='timed out'):
        asyncio.run(model_catalog.update_models(timeout=0.02))
    assert (process.pid, signal.SIGKILL) in signals
    assert process.killed and process.reaped


def test_cancel_during_spawn_still_owns_and_reaps_child(monkeypatch, tmp_path):
    process = Process(None)
    spawning = threading.Event()
    release = threading.Event()
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))

    def popen(*args, **kwargs):
        spawning.set()
        release.wait(2)
        return process

    def killpg(*args):
        process.kill()

    monkeypatch.setattr(model_catalog.subprocess, 'Popen', popen)
    monkeypatch.setattr(model_catalog.os, 'killpg', killpg)

    async def check():
        task = asyncio.create_task(model_catalog.update_models())
        await asyncio.to_thread(spawning.wait, 2)
        task.cancel()
        await asyncio.sleep(0)
        task.cancel()  # repeated cancellation must not abandon the worker
        release.set()
        with pytest.raises(asyncio.CancelledError):
            await task
        assert process.killed and process.reaped

    asyncio.run(check())


def test_spawn_error_sanitized(monkeypatch, tmp_path):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))

    def fail(*args, **kwargs):
        raise OSError('Authorization: fake-secret')

    monkeypatch.setattr(model_catalog.subprocess, 'Popen', fail)
    with pytest.raises(PiControlError) as error:
        asyncio.run(model_catalog.update_models())
    assert 'fake-secret' not in str(error.value)


@pytest.mark.parametrize('cancel', [False, True])
def test_real_subprocess_suppresses_output_and_is_reaped(monkeypatch, tmp_path, capsys, cancel):
    script = tmp_path / 'fake-pi'
    script.write_text(f'#!{sys.executable}\nimport time\nprint("fake-private-diagnostic", flush=True)\ntime.sleep(60)\n')
    script.chmod(0o700)
    monkeypatch.setattr(model_catalog, 'pi_cmd_for_role', lambda role: [str(script)])
    original = subprocess.Popen
    created = []

    def spawn(*args, **kwargs):
        process = original(*args, **kwargs)
        created.append(process)
        return process

    monkeypatch.setattr(model_catalog.subprocess, 'Popen', spawn)

    async def check():
        task = asyncio.create_task(model_catalog.update_models(timeout=0.15))
        if cancel:
            while not created:
                await asyncio.sleep(0.005)
            task.cancel()
        with pytest.raises(asyncio.CancelledError if cancel else PiControlError):
            await task
        assert len(created) == 1 and created[0].returncode is not None

    asyncio.run(check())
    assert capsys.readouterr() == ('', '')


def test_outer_timeout_cannot_exceed_android_read_budget(tmp_path, monkeypatch):
    monkeypatch.setenv('PI_CODING_AGENT_DIR', str(tmp_path))
    with pytest.raises(ValueError):
        asyncio.run(model_catalog.update_models(timeout=26))
