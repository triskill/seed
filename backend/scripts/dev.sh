#!/usr/bin/env bash
# Dev startup script for the Seed v0.1 backend (Task 0.6).
#
# Starts the FastAPI orchestrator with --reload. The orchestrator's
# lifespan spawns and supervises the Flask webapp subprocess, so this
# single command brings up the whole host dev stack. Stop with Ctrl-C;
# uvicorn's signal handling triggers the FastAPI lifespan shutdown,
# which stops Flask cleanly.

set -euo pipefail

# Resolve script directory and the repo root (one level above backend/).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Activate the worktree venv if it lives at the repo root.
if [ -f "$REPO_ROOT/.venv/bin/activate" ]; then
    # shellcheck disable=SC1091
    source "$REPO_ROOT/.venv/bin/activate"
fi

# Defaults — override via env if you need a different bind address.
HOST="${SEED_BACKEND_HOST:-127.0.0.1}"
PORT="${SEED_BACKEND_PORT:-7777}"

# `exec` so uvicorn becomes the foreground process and receives
# signals directly (cleaner Ctrl-C behaviour than a bash wrapper).
exec uvicorn seed_backend.service:app --host "$HOST" --port "$PORT" --reload
