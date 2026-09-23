"""Shared native Pi configuration persistence."""
import json
import os
import tempfile
import fcntl
from contextlib import contextmanager
from pathlib import Path

from seed_backend.provider_allowlist import PROVIDERS


def agent_dir():
    if value := os.environ.get('PI_CODING_AGENT_DIR'):
        return Path(value)
    if Path('/home/seed').is_dir():
        return Path('/home/seed/.pi/agent')
    return Path.home() / '.local/share/seed/pi-agent'


def read_json(name):
    path = agent_dir() / name
    try:
        data = json.loads(path.read_text())
    except FileNotFoundError:
        return {}
    if not isinstance(data, dict):
        raise ValueError('invalid Pi configuration')
    return data


class ConfigConflictError(Exception):
    """A shell Pi writer changed a file during our read/modify/write."""


_UNSET = object()


def write_json(name, data, *, expected=_UNSET):
    directory = agent_dir()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix='.seed-', dir=directory)
    try:
        with os.fdopen(fd, 'w') as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(data, stream, indent=2)
            stream.write('\n')
            stream.flush()
            os.fsync(stream.fileno())
        if expected is not _UNSET and _snapshot(directory / name) != expected:
            raise ConfigConflictError('Pi configuration changed')
        os.replace(temporary, directory / name)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def _snapshot(path):
    try:
        stat = path.stat()
        return (stat.st_dev, stat.st_ino, stat.st_size, stat.st_mtime_ns, path.read_bytes())
    except FileNotFoundError:
        return None


@contextmanager
def _locked():
    directory = agent_dir()
    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd = os.open(directory / '.seed-config.lock', os.O_CREAT | os.O_RDWR, 0o600)
    try:
        fcntl.flock(fd, fcntl.LOCK_EX)
        yield
    finally:
        fcntl.flock(fd, fcntl.LOCK_UN)
        os.close(fd)


def config():
    auth = read_json('auth.json')
    settings = read_json('settings.json')
    return {'providers': sorted(key for key in auth if isinstance(key, str)),
            **{key: settings[key] for key in ('defaultProvider', 'defaultModel', 'defaultThinkingLevel') if key in settings}}


def save_provider(provider, api_key):
    if provider not in PROVIDERS:
        raise ValueError('unsupported provider')
    with _locked():
        expected = _snapshot(agent_dir() / 'auth.json')
        auth = read_json('auth.json')
        auth[provider] = {'type': 'api_key', 'key': api_key}
        write_json('auth.json', auth, expected=expected)


def save_selection(provider, model_id, thinking_level):
    with _locked():
        expected = _snapshot(agent_dir() / 'settings.json')
        settings = read_json('settings.json')
        settings.update(defaultProvider=provider, defaultModel=model_id)
        if thinking_level is not None:
            settings['defaultThinkingLevel'] = thinking_level
        write_json('settings.json', settings, expected=expected)


def restore_settings(before, selection):
    with _locked():
        expected = _snapshot(agent_dir() / 'settings.json')
        current = read_json('settings.json')
        if current.get('defaultProvider') != selection.provider or current.get('defaultModel') != selection.model_id:
            raise ConfigConflictError('Pi configuration changed')
        if selection.thinking_level is not None and current.get('defaultThinkingLevel') != selection.thinking_level:
            raise ConfigConflictError('Pi configuration changed')
        write_json('settings.json', before, expected=expected)


def has_auth(provider):
    return bool(isinstance(provider, str) and provider and
                isinstance(read_json('auth.json').get(provider), dict))
