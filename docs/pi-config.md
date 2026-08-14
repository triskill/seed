# Pi config (project-local)

The orchestrator spawns two `pi` subprocesses — the
**middle-man** (intent extraction) and the **worker**
(builder) — to drive the agent loop. This file documents
how the project pins the *which `pi`, with which model,
with which config* decisions so they're independent of
the developer's global `~/.pi/agent/`.

## TL;DR

```bash
# 1. Set the API key (one-time, in your shell rc).
export OPENCODE_API_KEY="sk-..."

# 2. Start the dev server — the orchestrator handles the
#    rest (creates .pi/agent/ on first run, points the
#    child pi processes at it).
./backend/scripts/dev.sh
```

## What lives where

| Path | Committed? | Purpose |
|---|---|---|
| `.pi/agent/settings.json` | ✅ yes | Project defaults: `defaultProvider`, `defaultModel`, `defaultThinkingLevel`. |
| `.pi/agent/.gitignore` | ✅ yes | Ignores `auth.json`, `sessions/`, `bin/`, `git/`, `npm/`, `prompts/`, `models.json`, `.cache/` — anything pi creates at runtime. |
| `OPENCODE_API_KEY` env var | (your shell) | The API key for the `opencode-go` provider. **Never** commit. |
| `~/.pi/agent/` (global) | (your home) | Your personal pi config. **Not** used by the orchestrator. |

The orchestrator overrides `PI_CODING_AGENT_DIR` to
`<repo>/.pi/agent` before spawning each `pi`, so the
child pi processes read the project's local config and
ignore the user's `~/.pi/agent/` entirely (built-in
providers/models are still in the binary, so they work
fine without the global config).

## The defaults

The defaults pinned by `.pi/agent/settings.json` and
`pi_cmd_for_role()` in `seed_backend/orchestrator.py`:

| Field | Value | Why |
|---|---|---|
| `defaultProvider` | `opencode-go` | Cheap tier, works well for testing. |
| `defaultModel` | `deepseek-v4-flash` | Fast + cheap. The `pi --list-models` output shows it at ~1M context, `thinking: yes`. |
| `defaultThinkingLevel` | `low` | A "flash" tier model with high thinking is overkill; `low` is fast and good enough for the orchestrator's prompts. |

The `pi_cmd_for_role` helper also passes these
explicitly on the command line:

```python
["pi",
 "--mode", "rpc",
 "--provider", "opencode-go",
 "--model", "deepseek-v4-flash",
 "--thinking", "low",
 "--no-session"]
```

The middle-man invocation additionally receives:

```python
["--tools", "read,grep,find,ls"]
```

The same allowlist is enforced again by `PiRunner` when it reads pi's
RPC tool events. The worker does not receive `--tools` and keeps the
editing tools it needs.

This is belt-and-braces: the local `settings.json` is the
default, and the CLI flags override whatever's in there.
A misconfigured local file can't silently route to a
different model.

The Android runtime uses Alpine 3.22.5 so its packaged Node is version 22.
This is required by pi 0.80.3's undici dependency; Alpine 3.20's Node 20
failed at startup with `webidl.util.markAsUncloneable is not a function`.
The runtime Docker build executes `pi --version` as a compatibility smoke test.

## Overriding for a single run

Three env vars let you swap the model/provider without
touching code or the local config:

```bash
# Use Claude Haiku instead of DeepSeek for a one-off run.
SEED_PI_PROVIDER=anthropic \
SEED_PI_MODEL=claude-haiku-4-5 \
SEED_PI_THINKING=off \
./backend/scripts/dev.sh
```

| Env var | Default | What it does |
|---|---|---|
| `SEED_PI_PROVIDER` | `opencode-go` | The pi provider (e.g. `anthropic`, `openai`, `opencode-go`). |
| `SEED_PI_MODEL` | `deepseek-v4-flash` | The model ID within the provider. |
| `SEED_PI_THINKING` | `low` | Thinking level: `off`, `minimal`, `low`, `medium`, `high`, `xhigh`. |

The orchestrator also supplies runtime app context to the agents:

| Env var | Host default | Embedded default | Purpose |
|---|---|---|---|
| `SEED_APP_PATH` | `<repo>/webapp` via `dev.sh` | `/home/seed/app` | Mutable webapp workspace. |
| `SEED_APP_URL` | `http://127.0.0.1:7778` | `http://127.0.0.1:7777` | Mode-aware URL used by the worker for `curl` verification. |

`SEED_APP_URL` is selected after the service knows whether Flask started
as a host subprocess or fell back to the embedded WSGI mount. An explicit
`SEED_APP_URL` overrides that selection for custom development layouts.
The read-only middle-man cannot expand environment variables through a
shell, so its resolved workspace path is also appended as literal system
context at spawn time.

The matching `*_API_KEY` env var is picked up
automatically by pi (e.g. `ANTHROPIC_API_KEY` for
`anthropic`, `OPENAI_API_KEY` for `openai`,
`OPENCODE_API_KEY` for both `opencode` and
`opencode-go`). See the `env-api-keys` table in
`@earendil-works/pi-ai` for the full list.

### Android credential startup

On Android, provider and model are read from DataStore and the API key is read
from EncryptedSharedPreferences/Android Keystore whenever `RuntimeService`
creates a new PRoot process. The service injects `SEED_PI_PROVIDER`,
`SEED_PI_MODEL`, and one explicitly allowlisted provider credential variable
into uvicorn's environment; `pi_env_for_role()` passes them to both pi children.
The key is never placed in argv, loopback `PUT /config`, or the guest's plaintext
`config.json`. A fresh install with no saved form keeps the packaged
`opencode-go` / `deepseek-v4-flash` defaults.

Settings saved while PRoot is already alive take effect on the next real runtime
process generation (service cold start or crash replacement). A deliberate live
restart/apply control remains Phase 10 work.

## Why no `auth.json` in the project?

pi's `~/.pi/agent/auth.json` stores API keys on disk
(mode 600). We deliberately **don't** mirror that in
`.pi/agent/` because:

1. **Secrets in a shared repo are a footgun** — even a
   private repo leaks keys via `git log` history, CI
   runners, backups, etc.
2. **Per-developer keys are easier to rotate** — env
   vars are session-scoped, so revoking a key is a
   matter of re-exporting or removing from `.envrc`.
3. **The local `settings.json` is enough** — pi falls
   back to env vars for API keys, and the env is
   inherited by the child process.

If you want a `auth.json` locally (e.g. for offline
testing), create `.pi/agent/auth.json` with your key —
it's gitignored. The orchestrator will pick it up
because `PI_CODING_AGENT_DIR` points there.

## Testing without an API key

The `fake_pi*.py` fixtures under `backend/tests/fixtures/`
speak the same wire format as real `pi` but don't make
any LLM calls. The full test suite uses them; you don't
need an API key to run the tests:

```bash
.venv/bin/python -m pytest backend/ webapp/ -v
```

The Phase 3 manual demo (`scripts/demo_phase3.py`) also
uses the fake fixtures — it monkey-patches
`pi_cmd_for_role` to point at the fakes, so no real
`pi` is spawned and no API key is needed.

## Files touched

- `backend/seed_backend/orchestrator.py` — `pi_cmd_for_role`
  now returns the explicit flags; new `pi_env_for_role`
  returns the env dict with `PI_CODING_AGENT_DIR`
  pointing at the local config.
- `backend/seed_backend/pi_runner.py` — `PiRunner.__init__`
  accepts an optional `env` dict. When set, the runner
  uses `os.execvpe` instead of `os.execvp` so the child
  process gets the explicit env.
- `backend/seed_backend/service.py` — lifespan passes
  `pi_env_for_role(role)` to each `PiRunner`.
- `backend/tests/test_pi_config.py` — 7 new tests
  locking in the defaults and the `SEED_PI_*` override
  knobs.
- `backend/tests/test_pi_runner.py` — 2 new tests for
  the new `env` kwarg.
- `.pi/agent/settings.json` — the local defaults.
- `.pi/agent/.gitignore` — keeps runtime files out of
  git.
