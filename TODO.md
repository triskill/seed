# Seed — TODO

A self-improving Android app: the APK is an immutable shell with four screens
(App, Chat, Shell, Settings); an embedded Linux runtime (proot + Alpine) hosts
a Python orchestrator that drives two `pi` agent instances (a "middle-man" for
intent and a "worker" for building); the worker mutates a Flask + SQLite web
app inside the runtime; the App screen shows the result.

**Full design:** [`docs/plans/2026-06-30-seed-app-design.md`](docs/plans/2026-06-30-seed-app-design.md) (494 lines, vision + architecture + risks).
**Detailed phase tasks:** [`docs/plans/2026-06-30-seed-v0.1-bootstrap.md`](docs/plans/2026-06-30-seed-v0.1-bootstrap.md) (Phase 0 + Phase 1 task-by-task spec).

---

## Status at a glance

| Phase | What | Status |
|---|---|---|
| 0 | Project skeleton + local backend + web app | ✅ done (8/8) |
| 1 | Shell endpoint (PTY-backed) | ✅ done (5/5) |
| 2 | pi runner (PTY wrapper, ANSI strip, tool filter) | ✅ done (6/6) |
| 3 | Middle-man + worker orchestration | ✅ done (7/7) |
| 4 | System prompts + first real agent loop | ⬜ not started |
| 5 | Android shell (4 screens, nav, WebView) | ⬜ not started |
| 6 | Android ↔ backend wiring | ⬜ not started |
| 7 | Runtime extraction (proot + Alpine) | ⬜ not started |
| 8 | Foreground service | ⬜ not started |
| 9 | First-run setup wizard | ⬜ not started |
| 10 | End-to-end polish | ⬜ not started |

Tests: **59/59 passing** on a clean venv.

---

## ✅ Phase 0 — Project skeleton + local backend + web app

| # | Task | Files | Notes |
|---|---|---|---|
| 0.1 | Init repo structure | `backend/`, `webapp/` | Both packages use hatchling; minimal `pyproject.toml`. |
| 0.2 | FastAPI service with `/health` | `backend/seed_backend/service.py` | Skeleton FastAPI app. |
| 0.3 | Config loading from `config.json` | `backend/seed_backend/config.py` | Small `Config` dataclass; load/save JSON; default ports `{backend: 7777, flask: 7778}`. |
| 0.4 | Web app `/api/ping` endpoint | `webapp/seed_app/app.py` | Flask app with `/` (placeholder card) and `/api/ping` (readiness signal). |
| 0.5 | Wire Flask into backend | `backend/seed_backend/flask_manager.py` | `FlaskManager` spawns Flask via `asyncio.create_subprocess_exec`, polls `/api/ping` for readiness, terminates cleanly. FastAPI lifespan owns it. `/health` reports `{"status":"ok","flask":"up|down"}`. |
| 0.6 | Dev startup script | `backend/scripts/dev.sh` | One command (`./backend/scripts/dev.sh`) brings up the full dev stack. |
| 0.7 | README with quickstart | `README.md` | Project intro + quick start. |
| 0.8 | Phase 0 demo | (verification) | Manual: curl `/health`, `/`, `/api/ping` against running stack — all green. |

---

## ✅ Phase 1 — Shell endpoint (PTY-backed)

Endpoint: **`POST /shell/exec`** — accepts `{"command": "..."}`, returns
`{"stdout", "stderr", "exit_code", "captured_ansi", "truncated"}`.

| # | Task | Files | Notes |
|---|---|---|---|
| 1.1 | Basic `/shell/exec` (subprocess) | `backend/seed_backend/shell.py`, `service.py` | Initial `asyncio.create_subprocess_exec` version (later rewritten by 1.2). Pydantic request/response models in `service.py`. Server-side 60s timeout (`SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS`). `min_length=1` on the command field. |
| 1.2 | PTY-based execution | `backend/seed_backend/shell.py` | Rewrote to use `pty.openpty()` + `os.fork()` in a thread-pool executor. Child does `setsid()` + `dup2(slave, 0/1/2)` + `execvp("sh", ["sh", "-c", cmd])`. PTY put in raw mode so `echo hi\n` round-trips. ANSI codes preserved. `capture_ansi` option on `ExecResult`. |
| 1.3 | Output truncation | `backend/seed_backend/shell.py` | `MAX_LINES = 5000`, `MAX_BYTES = 1 MiB`. Read loop stops accumulating once either cap is exceeded; PTY is still drained so the child never blocks. `truncated: bool` field added to `ExecResult` + `ShellExecResponse`. |
| 1.4 | Cancellation | `backend/seed_backend/shell.py` | New `ExecCancelled` exception. `exec_command(..., cancel: asyncio.Event)` parameter. Watcher coroutine races the read via `asyncio.wait`; on signal, sends SIGTERM to the process group (`os.killpg`), closes the master fd to unblock the in-flight read, then SIGKILL after a 1s grace period. |
| 1.5 | Working directory persistence | `backend/seed_backend/shell.py` | `ShellSession` class with `cwd: Path` attribute. Heuristic `^\s*cd\s+(\S+)(?:\s+(.*))?$` resolves, validates, and updates `cwd`; the rest of the command runs in the new cwd. Lifespan creates a single `app.state.shell_session`; the route uses it. Module-level `exec_command` kept as a stateless shortcut. |

**Module shape after Phase 1:**
- `shell.py` — `ExecResult`, `ExecCancelled`, `MAX_LINES`, `MAX_BYTES`, `_exec_command_impl`, `exec_command`, `ShellSession`.
- `service.py` — `app`, lifespan, `/health`, `POST /shell/exec` (with `ShellExecRequest`/`ShellExecResponse`).

---

## ✅ Phase 2 — pi runner (PTY wrapper, ANSI strip, tool filter)

| # | Task | Files | Notes |
|---|---|---|---|
| 2.1 | Fake pi for testing | `backend/tests/fixtures/fake_pi.py` | Python script that reads stdin, writes 3 JSONL progress events + "done", exits 0. |
| 2.2 | PTY spawn + read loop | `backend/seed_backend/pi_runner.py` | `PiRunner.__init__(cmd, role, ...)`, `start()`, `send()`, `read_lines()` async generator. Uses `pty.openpty()` + `os.fork()` + `os.execvp` like `shell.py`. Per-runner `ThreadPoolExecutor` (avoids default-executor fragility under multi-threaded pytest). Child reports its post-setsid pgid through a pipe; stop() uses that for killpg (with a fallback to `os.kill(pid, ...)` if setsid failed). Only one `os.waitpid` call site, in `stop()`. |
| 2.3 | Output ANSI strip | `backend/seed_backend/pi_runner.py`, `backend/tests/test_ansi_strip.py` | `PiRunner(strip_ansi=True)` (default on). Two module-level regexes — CSI (`ESC [`) and OSC (`ESC ]`) — applied on every line in the read loop. |
| 2.4 | Tool-call filter (middle-man read-only) | `backend/seed_backend/pi_runner.py`, `backend/tests/test_tool_filter.py` | `PiRunner(read_only_tools={read,grep,find,ls})`. Read loop parses each line as JSON, looks for `type == "tool_execution_start"`, aborts child (SIGTERM) and raises `ToolCallBlocked(tool_name, event)` from `read_lines()` if the tool isn't allowed. Also: fixed a real PTY-EOF bug exposed by the tests (master fd returns `OSError(errno=EIO)` on EOF, not 0 bytes — see `seed_backend/pi_runner.py` `_read_loop`); the reader now signals EOF via a `None` sentinel. |
| 2.5 | System prompt preload | `backend/seed_backend/pi_runner.py`, `backend/tests/test_system_prompt.py` | `PiRunner(system_prompt=...)`. After fork but before `start()` returns, the runner writes the prompt + blank line to the child's stdin. Extracted into `_do_preload()` so auto-restart reuses it. |
| 2.6 | Auto-restart on crash | `backend/seed_backend/pi_runner.py`, `backend/tests/test_auto_restart.py` | `PiRunner(auto_restart=False, max_restarts=5)`. The reader's EOF path calls `_restart()` (which calls `_do_fork()` + `_do_preload()`) up to `max_restarts` times; after that the stream ends. Default off to avoid surprising loops; production turns it on. |

**Module shape after Phase 2:**
- `pi_runner.py` — `PiRunner`, `PiRunnerNotRunning`, `ToolCallBlocked`, `_strip_ansi`, `_check_tool_call`, `_ANSI_CSI_RE`, `_ANSI_OSC_RE`.
- `tests/fixtures/fake_pi*.py` — 4 siblings: basic progress emitter, ANSI-coloured progress emitter, tool-event emitter, crash-on-exit emitter.

**Key design notes** (full rationales in the module docstrings):
- Per-runner `ThreadPoolExecutor` so each runner owns its worker threads; shut down deterministically in `stop()`. The default asyncio executor is process-wide and gets fragile when multiple runners are torn down in sequence.
- Only one `os.waitpid` call site, in `stop()`. Two threads in `waitpid` for the same pid is a footgun (kernel delivers exit to only one; the other blocks forever). EOF on the PTY master is the canonical "child is gone" signal.
- Identity pipe: child reports its post-setsid pgid back to the parent. `os.getpgid(pid)` from the parent is unreliable in pytest's multi-threaded context (setsid can silently fail in the child, leaving it in the parent's pgid).

## ✅ Phase 3 — Middle-man + worker orchestration (7/7)

| # | Task | Files | Notes |
|---|---|---|---|
| 3.1 | Service spawns both pi instances on startup | `backend/seed_backend/orchestrator.py`, `backend/seed_backend/service.py`, `backend/tests/test_service_lifecycle.py` | `Orchestrator` lives in its own module (not `service.py`) to avoid the `service.py <-> chat.py` import cycle that would otherwise need a `TYPE_CHECKING` workaround. The class is a thin container for the middle-man + worker `PiRunner`s: `start()` forks+execs both, `stop()` reaps both, plus a per-subscriber pub-sub queue (`subscribe` / `unsubscribe` / `_broadcast`) for the chat route. Lifespan brings the orchestrator up on app start, down on shutdown, and is tolerant of `pi` not being installed (the spawn failure is swallowed and `/health` still works; the orchestrator's pids get set so `stop()` can reap the dead child). |
| 3.2 | WebSocket `/chat` endpoint | `backend/seed_backend/chat.py`, `backend/seed_backend/service.py`, `backend/tests/fixtures/fake_pi_log.py`, `backend/tests/test_chat_ws.py` | `@app.websocket("/chat")` route delegates to `chat.handle_chat`. The handler accepts the upgrade, loops on `receive_text` for `{"type": "user_message", "text": ...}` frames, and forwards each to `orchestrator.send_to_middleman`. Non-JSON / unknown-type frames are logged + dropped (forward-compat for new message kinds in later phases). A `fake_pi_log.py` fixture writes the received prompt to a log file so the test can verify the round-trip without reaching into the runner's internal queue from a different event loop. |
| 3.3 | Stream middle-man output to chat WS | `backend/seed_backend/orchestrator.py`, `backend/seed_backend/chat.py`, `backend/tests/test_middleman_stream.py` | `Orchestrator.start()` spawns a background read loop that consumes `middleman.read_lines()` and broadcasts each line as `{"type": "middleman_line", "line": <raw>}` to every subscriber queue. `chat.handle_chat` subscribes a private queue on accept, spawns a forwarder task that pumps `queue -> ws.send_text(json)`, and unsubscribes in a `finally` block on disconnect. Per-subscriber queue is capped at 256 — slow clients drop events rather than backpressure the reader. |
| 3.4 | Dispatch JSON detection | `backend/seed_backend/middleman.py`, `backend/seed_backend/orchestrator.py`, `backend/tests/fixtures/fake_pi_dispatch.py`, `backend/tests/test_dispatch.py`, `backend/tests/test_middleman_dispatch.py` | New module `middleman.py` owns the regex `r'```json\n(.*?)\n```'` (per the plan spec) and the parse step. The middle-man read loop keeps a rolling scan buffer (capped at 64 KiB as a safety net) and, on a match, calls `worker.send(json.dumps(dispatch) + "\n")`. The dispatch block is *also* broadcast as `middleman_line` events so the chat UI can render it as a card. Worker send failures are logged + swallowed; the chat stream must not die because the worker is unhealthy. |
| 3.5 | Worker stream → chat stream | `backend/seed_backend/orchestrator.py`, `backend/tests/fixtures/fake_pi_worker_response.py`, `backend/tests/test_worker_stream.py` | Replaces the no-op worker read loop stub with the real broadcast-each-line loop, tagged `{"type": "worker_line", "line": ...}` so the chat UI can render the two agents' output distinctly (thought vs build progress). |
| 3.6 | Complete signal + app reload trigger | `backend/seed_backend/events.py`, `backend/seed_backend/orchestrator.py`, `backend/tests/fixtures/fake_pi_worker_response.py`, `backend/tests/test_complete_signal.py` | New `events.py` module centralises the `<task:done/>` marker string and the WS `type` values (single source of truth for the orchestrator + chat layer + future prompt templates). Worker read loop watches for the marker; on detection, broadcasts two events to all subscribers — `{"type": "complete", "summary": "Task complete"}` and `{"type": "app_reload"}`. The marker line itself is *not* broadcast as a worker_line (it's a control signal, not user-facing content). The `summary` is a v0.1 placeholder string; Phase 4 will enrich it (the worker prompt will tell the agent to append a human-readable summary after the marker). |
| 3.7 | Phase 3 demo | `backend/scripts/demo_phase3.py`, `backend/pyproject.toml` | `scripts/demo_phase3.py` is a self-contained manual demo: monkey-patches `pi_cmd_for_role` to use the fake fixtures, starts uvicorn on a free port in a daemon thread, opens a real WebSocket connection with `websocket-client`, sends one `user_message`, and prints every event the chat stream emits. Also added `uvicorn[standard]>=0.27` to the backend deps — the production server needs a real ASGI WS library to handle the `/chat` upgrade (the starlette TestClient uses its own transport, so tests don't need it). |

**Module shape after Phase 3:**
- `orchestrator.py` — `Orchestrator`, `pi_cmd_for_role`, pub-sub + per-role read loops. Middle-man loop scans for dispatch JSON and broadcasts `middleman_line` events. Worker loop broadcasts `worker_line` events and detects the `<task:done/>` marker for `complete` + `app_reload`.
- `chat.py` — `handle_chat`, `_forward_events`. WS handler + forwarder task.
- `service.py` — lifespan creates the orchestrator + `/chat` route delegates to `handle_chat`.
- `events.py` — `TASK_DONE_MARKER` (`<task:done/>`) + WS `type` constants. New in 3.6.
- `middleman.py` — `DISPATCH_RE` + `extract_dispatch`. New in 3.4.
- `tests/_ws_helpers.py` — `Receiver`, `collect_all`, `drain`, `filter_by_type`. New in 3.6; replaces ad-hoc helpers previously inlined in the WS tests. `Receiver` runs `ws.receive_text()` in a daemon thread with a per-call timeout via a queue, so a missing stream fails fast (`TimeoutError`) instead of hanging the test process.
- `tests/fixtures/fake_pi_dispatch.py` — middle-man that emits a fenced JSON dispatch block. New in 3.4.
- `tests/fixtures/fake_pi_worker_response.py` — worker that emits 3 progress events + `<task:done/>`. New in 3.5, extended in 3.6.
- `tests/test_service_lifecycle.py` — 4 tests (lifespan wiring, roles, shutdown, missing-pi tolerance).
- `tests/test_chat_ws.py` — 3 tests (forward to middleman, not to worker, accepts connection).
- `tests/test_middleman_stream.py` — 3 tests (3-event burst, prompt echo, subscriber cleanup).
- `tests/test_dispatch.py` — 1 end-to-end test (dispatch → worker stdin). New in 3.4.
- `tests/test_middleman_dispatch.py` — 6 unit tests for `extract_dispatch` (no block, single-line, multi-line, multiple blocks, invalid JSON). New in 3.4.
- `tests/test_worker_stream.py` — 2 tests (3 worker_line frames; worker + middleman distinguishable on the same WS). New in 3.5.
- `tests/test_complete_signal.py` — 2 tests (complete + app_reload pair; multi-client fan-out). New in 3.6.

## ⬜ Phase 4 — System prompts + first real agent loop

4 tasks: write `middleman.md` + `worker.md`, manual end-to-end test with real
`pi`, iterate on prompts.

## ⬜ Phase 5 — Android shell (4 screens, nav, WebView)

9 tasks: Gradle init, 4-section nav, WebView in App screen, Chat/Shell/Settings
screen UIs, DataStore for settings, theme + polish, Phase 5 demo.

## ⬜ Phase 6 — Android ↔ backend wiring

5 tasks: Retrofit + OkHttp client, WebSocket chat client, wire Chat/Shell/
Settings screens.

## ⬜ Phase 7 — Runtime extraction

4 tasks: acquire proot + Alpine rootfs, build a deployable runtime,
`RuntimeExtractor`, first-run trigger.

## ⬜ Phase 8 — Foreground service

4 tasks: `ProotRunner`, `RuntimeService` (foreground), health check polling,
boot receiver.

## ⬜ Phase 9 — First-run setup wizard

3 tasks: `SetupScreen`, wire `MainActivity`, cold-start timing.

## ⬜ Phase 10 — End-to-end polish

6 tasks: App screen auto-reload, error banners, cancel button in Shell,
"Add a habit tracker" full demo, polish + edge cases, final demo.

---

## Known v0.1 limitations (carry-forward TODOs)

- **`os.fork()` from a multi-threaded process emits a `DeprecationWarning` in Python 3.12+.** Safe in practice here (child immediately `execvp`s — no Python state is touched) but the long-term fix is a `subprocess.Popen` + PTY abstraction or a dedicated single-threaded worker process. Phase 2+ is a good time to address.
- **Stderr is merged into stdout** under PTY (both go to the slave). The response shape keeps `stderr: ""` for back-compat. A richer wire format (separate channels) is a later task if any client needs it.
- **`ShellSession` cwd tracking is heuristic**, not a true persistent shell: only a leading `cd <path>` is recognised; `cd /tmp && ls` updates `cwd` for the Python side but the `ls` runs in the updated cwd. `cd` inside `$()` or backticks is not tracked. A real persistent shell process is a future task.
- **No CI** — tests are run locally. A GitHub Actions workflow (or equivalent) is a future task.
- **No `mypy`/lint config** in the repo. v0.1 is "ship it"; static analysis is a future task.
- **No git remote** — Phase 0/1 work was merged locally to `master` only. Push + PR is a future task.

---

## Quick reference

**Run the dev stack:**
```bash
python3 -m venv .venv
.venv/bin/pip install -e "./backend[dev]" -e "./webapp[dev]"
./backend/scripts/dev.sh   # starts uvicorn (which spawns Flask via lifespan)
# in another shell:
curl http://127.0.0.1:7777/health    # {"status":"ok","flask":"up"}
curl -X POST http://127.0.0.1:7777/shell/exec -H 'Content-Type: application/json' \
     -d '{"command": "ls --color=auto /tmp"}'
```

**Run all tests:**
```bash
.venv/bin/python -m pytest backend/ webapp/ -v
# 59 passed
```

**Run the Phase 3 manual demo (no real pi / API key needed):**
```bash
.venv/bin/python backend/scripts/demo_phase3.py
# streams middleman_line → worker_line → complete → app_reload
```

**Worktree workflow for new phases:**
```bash
git worktree add .worktrees/<phase-name> -b feat/<phase-name>
cd .worktrees/<phase-name>
python3 -m venv .venv && .venv/bin/pip install -e "./backend[dev]" -e "./webapp[dev]"
# ... implement, test, commit ...
# when done: merge to master, remove worktree, delete branch
```
