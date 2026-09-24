"""Private, atomic snapshot of the latest Worker task (never an execution queue)."""
import json
import os
import tempfile
from pathlib import Path


class TaskStore:
    def __init__(self, directory: Path):
        self.directory = Path(directory)
        self.path = self.directory / 'task-status.json'

    def load(self) -> dict | None:
        try:
            data = json.loads(self.path.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            return None
        if (not isinstance(data, dict) or data.get('type') != 'task_status'
                or not isinstance(data.get('taskId'), str)
                or data.get('status') not in {'pending', 'running', 'cancel_pending', 'cancelled', 'completed', 'failed', 'interrupted'}):
            return None
        return data

    def load_events(self) -> list[dict]:
        try:
            data = json.loads((self.directory / 'task-events.json').read_text(encoding='utf-8'))
            return data if isinstance(data, list) else []
        except (OSError, ValueError):
            return []

    def save_events(self, events: list[dict]) -> None:
        allowed = {
            'task_status': ('type', 'taskId', 'status'),
            'task_outcome': ('type', 'taskId', 'status', 'source'),
            'role_health': ('type', 'role', 'status'),
            'error': ('type',),
        }
        safe = [{key: event[key] for key in allowed[event['type']] if key in event}
                for event in events if isinstance(event, dict) and event.get('type') in allowed]
        self._atomic_save(self.directory / 'task-events.json', safe[-512:])

    def save(self, status: dict) -> None:
        self._atomic_save(self.path, {k: v for k, v in status.items() if k in ('type', 'taskId', 'status')})

    def _atomic_save(self, path: Path, payload: object) -> None:
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix='.task-', dir=self.directory)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                os.fchmod(stream.fileno(), 0o600)
                json.dump(payload, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, path)
            directory_fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(name):
                os.unlink(name)
