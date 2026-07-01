# Seed

A self-improving Android app. The APK is an immutable shell with four
screens (App, Chat, Shell, Settings). An embedded Linux runtime
(proot + Alpine) hosts a Python orchestrator that drives two `pi` agent
instances — a "middle-man" for intent and a "worker" for building — and
the worker mutates a Flask + SQLite web app shown in a WebView. By the
end of day 1, the user has the web app they asked for.

## Status

**v0.1 bootstrap.** The repo currently contains:

- `backend/` — Python package (`seed_backend`) that will host the FastAPI
  orchestrator, pi runner, and Flask subprocess manager.
- `webapp/` — Flask app (`seed_app`) that the worker mutates.
- `docs/plans/` — full design and phased implementation plan.

The Android shell, the embedded Linux runtime, and the real agent loop
land in later phases.

## Quick start (host dev)

Requires Python 3.11+ (3.12 is what we test on) and the
`pi` CLI on `PATH` (v0.79+).

```bash
# Create a venv (PEP 668 system Pythons require this)
python3 -m venv .venv
source .venv/bin/activate

# Install both packages in editable mode
pip install -U pip hatchling
pip install -e "backend/[dev]"
pip install -e "webapp/[dev]"

# Smoke test (no API key needed — uses fake pi fixtures)
pytest backend/ webapp/ -v
# → 68 passed

# Run the dev server (orchestrator + Flask webapp)
./backend/scripts/dev.sh
# → curl http://127.0.0.1:7777/health

# Open a real chat session (Phase 3 demo, no API key needed)
.venv/bin/python backend/scripts/demo_phase3.py
# → streams middleman_line → worker_line → complete → app_reload
```

### Running the real agent loop

The orchestrator uses real `pi` when started via
`./backend/scripts/dev.sh`. You need an API key for the
provider/model in `.pi/agent/settings.json` (default:
`opencode-go` / `deepseek-v4-flash`). Set it in your
shell — **never commit it**:

```bash
export OPENCODE_API_KEY="sk-..."
./backend/scripts/dev.sh
```

See [`docs/pi-config.md`](docs/pi-config.md) for the full
configuration story (local `.pi/agent/`, env var
overrides, why no `auth.json` in the repo).

## Layout

```
backend/
  pyproject.toml
  seed_backend/        # FastAPI orchestrator (Task 0.2+)
  tests/               # pytest tests, currently a smoke import
webapp/
  pyproject.toml
  seed_app/            # Flask + SQLite app the worker mutates
    app.py
    templates/
    static/
docs/
  plans/
    2026-06-30-seed-app-design.md     # full design
    2026-06-30-seed-v0.1-bootstrap.md # 11-phase implementation plan
```

## Reference

- **Design:** [`docs/plans/2026-06-30-seed-app-design.md`](docs/plans/2026-06-30-seed-app-design.md)
- **Implementation plan:** [`docs/plans/2026-06-30-seed-v0.1-bootstrap.md`](docs/plans/2026-06-30-seed-v0.1-bootstrap.md)

## License

TBD. Note: proot (used in later phases) is GPL — see design doc §12.
