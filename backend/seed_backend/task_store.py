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

    def save(self, status: dict) -> None:
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        fd, name = tempfile.mkstemp(prefix='.task-', dir=self.directory)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                os.fchmod(stream.fileno(), 0o600)
                json.dump(status, stream)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(name, self.path)
            directory_fd = os.open(self.directory, os.O_RDONLY)
            try:
                os.fsync(directory_fd)
            finally:
                os.close(directory_fd)
        finally:
            if os.path.exists(name):
                os.unlink(name)
