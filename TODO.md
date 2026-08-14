# Seed — TODO

A self-improving Android app: the APK is an immutable shell with four screens
(App, Chat, Shell, Settings); an embedded Linux runtime (proot + Alpine) is
intended to host a Python orchestrator that drives two `pi` agent instances (a
"middle-man" for intent and a "worker" for building). The current webapp is a
minimal Flask mutation seed; SQLite-backed features are planned but not yet
implemented. Host development can run FastAPI on 7777 and Flask on 7778; the
Android prototype instead WSGI-mounts Flask into FastAPI on 7777. The App
screen shows the webapp, but the on-device two-agent loop remains blocked as
described below.

**Original design:** [`docs/plans/2026-06-30-seed-app-design.md`](docs/plans/2026-06-30-seed-app-design.md) (vision, architecture, and risks; some runtime details are superseded here).
**Original phased plan:** [`docs/plans/2026-06-30-seed-v0.1-bootstrap.md`](docs/plans/2026-06-30-seed-v0.1-bootstrap.md) (historical task-by-task specification; current status lives in this TODO).
**Pi config:** [`docs/pi-config.md`](docs/pi-config.md) — project-local `.pi/agent/`, default model (`deepseek-v4-flash` on `opencode-go`), `OPENCODE_API_KEY` env var, `SEED_PI_*` overrides.

---

## Status at a glance

_Status updated 2026-08-14 in the working tree based on `main` commit
`f54e771`. The embedded runtime bring-up itself was performed on `seed_dev`
x86_64 on 2026-08-12._

| Phase | What | Status |
|---|---|---|
| 0 | Project skeleton + local backend + web app | ✅ done (8/8) |
| 1 | Shell endpoint | ✅ done (5/5); `shell.py` uses `subprocess.Popen` with merged pipes so it works inside proot |
| 2 | pi runner (PTY wrapper, ANSI strip, tool filter) | ⚠️ host-complete (6/6); `PiRunner` still uses PTY + `fork`, so it cannot start inside Android proot |
| 3 | Middle-man + worker orchestration | ⚠️ host-complete (7/7); fake/real host flows work, but the embedded agent processes do not start |
| 4 | System prompts + first real agent loop | ⚠️ host demo done (4/4); not reproduced inside the standalone APK |
| 5 | Android shell (4 screens, nav, WebView) | ✅ done (9/9); verified on emulator 2026-08-12 |
| 6 | Android ↔ backend wiring | ✅ done (5/5) |
| 7 | Native proot packaging + rootfs extraction | ✅ done (5/5); verified on emulator |
| 8 | Foreground service | ✅ done (4/4); verified on emulator |
| 9 | First-run runtime startup gate | ✅ done (4/4); verified on emulator |
| 10 | Embedded agent loop + end-to-end polish | ⬜ partial; Android-compatible agent launch is the release blocker, followed by reload, security, cancellation, recovery, and UX work |

**Embedded runtime shell verified on `seed_dev` x86_64 (2026-08-12; the agent loop is not end-to-end):**

* APK installs and launches without a host backend.
* `BootController` extracts the Alpine rootfs and version data to
  `filesDir/linux/`; PackageManager installs the selected
  Termux-Android-native PRoot executable, loader, and shared libraries under
  `applicationInfo.nativeLibraryDir`.
* `RuntimeService` runs proot, which launches uvicorn on
  `127.0.0.1:7777` inside the embedded Linux runtime.
* The Flask app is mounted **inside the FastAPI process** via
  `a2wsgi.WSGIMiddleware` (the Flask subprocess path returns `OSError
  38 (ENOSYS)` because `proot` on Android does not implement
  `fork(2)`; the WSGI mount is the v0.1 workaround).
* `/health` returns `{"status":"ok","flask":"up"}`; `/api/ping` returns
  `{"pong": true}`; `/` returns the Seed placeholder card.
* The App tab WebView loads the placeholder card.
* The Chat tab opens a WebSocket to `/chat` and forwards user
  messages; the orchestrator reports `"orchestrator not running"` for
  the agent stream because the pi subprocess spawn hits the same
  fork problem (next phase).
* The Shell tab runs Alpine commands; `echo hello` shows
  `$ echo hello` / `hello` / `[exit 0]`.

Verification on 2026-08-14: **124/124 backend tests** and **2/2 webapp
tests** pass, including the combined **126/126** Python run; **175/175 Android
JVM tests** pass. The Flask environment test is isolated from fixed port 7778,
removing its prior order-dependent readiness race. The runtime tooling shell
suite passes **30/30**. Android
`lintDebug` and
`assembleDebug` pass (0 lint errors, 27 warnings), and the instrumentation APK
compiles. The current instrumentation source contains **2 classes / 6 test
methods** (`StartRuntimeScreenTest` and `NativeProotSmokeTest`); these were not
run on a device during this verification.

> The phase tables below are an implementation history. Their per-task APK
> sizes, test counts, URLs, and "new/modified" annotations describe the state at
> that milestone; use **Status at a glance**, Phase 10, and **Known v0.1
> limitations** for the current state.

---

## ✅ Phase 0 — Project skeleton + local backend + web app

| # | Task | Files | Notes |
|---|---|---|---|
| 0.1 | Init repo structure | `backend/`, `webapp/` | Both packages use hatchling; minimal `pyproject.toml`. |
| 0.2 | FastAPI service with `/health` | `backend/seed_backend/service.py` | Skeleton FastAPI app. |
| 0.3 | Config loading from `config.json` | `backend/seed_backend/config.py` | Small `Config` dataclass; load/save JSON; default persisted/dev schema `{backend: 7777, flask: 7778}`. The embedded runtime currently serves both FastAPI and WSGI-mounted Flask on 7777, and startup does not consume this saved config. |
| 0.4 | Web app `/api/ping` endpoint | `webapp/seed_app/app.py` | Flask app with `/` (placeholder card) and `/api/ping` (readiness signal). |
| 0.5 | Wire Flask into backend | `backend/seed_backend/flask_manager.py` | `FlaskManager` spawns Flask via `asyncio.create_subprocess_exec`, polls `/api/ping` for readiness, terminates cleanly. FastAPI lifespan owns it. `/health` reports `{"status":"ok","flask":"up|down"}`. |
| 0.6 | Dev startup script | `backend/scripts/dev.sh` | One command brings up the host stack. It now exports an absolute `SEED_APP_PATH`, runs uvicorn from `backend/`, and `FlaskManager` resolves the same webapp independently of the caller's CWD. |
| 0.7 | README with quickstart | `README.md` | Project intro + quick start. |
| 0.8 | Phase 0 demo | (verification) | Manual: curl `/health`, `/`, `/api/ping` against running stack — all green. |

---

## ✅ Phase 1 — Shell endpoint (pipe-backed subprocess)

Endpoint: **`POST /shell/exec`** — accepts `{"command": "..."}`, returns
`{"stdout", "stderr", "exit_code", "truncated"}`. The library-level
`ExecResult` also tracks `captured_ansi`, but the HTTP response does not expose
that field.

| # | Task | Files | Notes |
|---|---|---|---|
| 1.1 | Basic `/shell/exec` (subprocess) | `backend/seed_backend/shell.py`, `service.py` | Initial `asyncio.create_subprocess_exec` version (later rewritten by 1.2). Pydantic request/response models in `service.py`. Server-side 60s timeout (`SHELL_EXEC_DEFAULT_TIMEOUT_SECONDS`). `min_length=1` on the command field. |
| 1.2 | Process execution | `backend/seed_backend/shell.py` | Originally PTY + `fork`; the current implementation uses `subprocess.Popen(["sh", "-c", cmd])`, merged stdout/stderr pipes, and a new process session. This loses automatic TTY colour but works inside Android proot, where `fork(2)` returns ENOSYS. `capture_ansi` remains on the library result for compatibility. |
| 1.3 | Output truncation | `backend/seed_backend/shell.py` | `MAX_LINES = 5000`, `MAX_BYTES = 1 MiB`. The pipe is drained after either cap is exceeded so the child cannot block. `truncated: bool` is exposed by `ExecResult` and `ShellExecResponse`. |
| 1.4 | Cancellation | `backend/seed_backend/shell.py` | `ExecCancelled` and `exec_command(..., cancel: asyncio.Event)` provide library-level cancellation. The watcher sends SIGTERM to the subprocess process group and escalates to SIGKILL after a 1s grace period. The HTTP endpoint and Android client do not yet expose real cancellation. |
| 1.5 | Working directory persistence | `backend/seed_backend/shell.py` | `ShellSession` class with `cwd: Path` attribute. Heuristic `^\s*cd\s+(\S+)(?:\s+(.*))?$` resolves, validates, and updates `cwd`; the rest of the command runs in the new cwd. Lifespan creates a single `app.state.shell_session`; the route uses it. Module-level `exec_command` kept as a stateless shortcut. |

**Module shape after Phase 1:**
- `shell.py` — `ExecResult`, `ExecCancelled`, `MAX_LINES`, `MAX_BYTES`, `_exec_command_impl`, `exec_command`, `ShellSession`.
- `service.py` — `app`, lifespan, `/health`, `POST /shell/exec` (with `ShellExecRequest`/`ShellExecResponse`).

---

## ✅ Phase 2 — pi runner (PTY wrapper, ANSI strip, tool filter)

| # | Task | Files | Notes |
|---|---|---|---|
| 2.1 | Fake pi for testing | `backend/tests/fixtures/fake_pi.py` | Python script that reads stdin, writes 3 JSONL progress events + "done", exits 0. |
| 2.2 | PTY spawn + read loop | `backend/seed_backend/pi_runner.py` | `PiRunner.__init__(cmd, role, ...)`, `start()`, `send()`, `read_lines()` async generator. Uses `pty.openpty()` + `os.fork()` + `os.execvp`; unlike the Shell executor, this has not yet been converted to an Android-compatible process launcher. Per-runner `ThreadPoolExecutor` (avoids default-executor fragility under multi-threaded pytest). Child reports its post-setsid pgid through a pipe; stop() uses that for killpg (with a fallback to `os.kill(pid, ...)` if setsid failed). Only one `os.waitpid` call site, in `stop()`. |
| 2.3 | Output ANSI strip | `backend/seed_backend/pi_runner.py`, `backend/tests/test_ansi_strip.py` | `PiRunner(strip_ansi=True)` (default on). Two module-level regexes — CSI (`ESC [`) and OSC (`ESC ]`) — applied on every line in the read loop. |
| 2.4 | Tool-call filter capability | `backend/seed_backend/pi_runner.py`, `backend/tests/test_tool_filter.py` | `PiRunner(read_only_tools={read,grep,find,ls})` can block disallowed `tool_execution_start` events, abort the child, and raise `ToolCallBlocked`. PTY EOF handling treats `EIO` as EOF. A Phase 10 follow-up now enables both pi's `--tools read,grep,find,ls` allowlist and the matching `PiRunner.read_only_tools` filter for the middle-man; the worker remains unrestricted. |
| 2.5 | System prompt preload | `backend/seed_backend/pi_runner.py`, `backend/tests/test_system_prompt.py` | `PiRunner(system_prompt=...)`. After fork but before `start()` returns, the runner writes the prompt + blank line to the child's stdin. Extracted into `_do_preload()` so auto-restart reuses it. |
| 2.6 | Auto-restart on crash | `backend/seed_backend/pi_runner.py`, `backend/tests/test_auto_restart.py` | `PiRunner(auto_restart=False, max_restarts=5)` can restart from the reader's EOF path up to the configured limit. The default is off, and current production wiring also leaves it off; crash supervision remains Phase 10 work. |

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

## ✅ Phase 4 — System prompts + first real agent loop (4/4)

| # | Task | Files | Notes |
|---|---|---|---|
| 4.1 | `middleman.md` system prompt | `backend/prompts/middleman.md` (new), `backend/seed_backend/orchestrator.py` (wired via `--append-system-prompt`) | Defines the middle-man's role: read-only access to `/home/seed/app/`, ask 1–2 clarifying questions if ambiguous, emit a fenced JSON dispatch block when ready, answer questions directly (no JSON). Describes the wire format the orchestrator expects (`{"intent","feature","spec"}`). Updated `pi_cmd_for_role` to pass `--append-system-prompt <file>` so the role-specific prompt is injected at spawn time. |
| 4.2 | `worker.md` system prompt | `backend/prompts/worker.md` (new), `backend/seed_backend/orchestrator.py` | Worker prompt: read state, plan, edit, verify (`curl` the new route, check the DB schema), emit `<task:done summary="..."/>` when done. Includes the `<task:done summary="..."/>` marker format the orchestrator's worker read loop watches for (Task 3.6 + Phase 4 enrichment). |
| 4.3 | Orchestrator speaks pi's RPC protocol | `backend/seed_backend/events.py`, `backend/seed_backend/orchestrator.py`, `backend/tests/fixtures/fake_pi*.py`, `backend/tests/test_events.py`, `backend/tests/test_prompts.py` | The orchestrator was built assuming pi outputs plain text. Real `pi --mode rpc` expects JSON commands on stdin (`{"type":"prompt","message":"..."}`) and emits JSONL events on stdout (`message_update`, `tool_execution_start`, `turn_end`, etc.). Added `translate_pi_line` (in `events.py`) that unwraps pi's events back to plain text deltas / tool-call JSON / turn-boundary signals. `send_to_middleman` and the worker send now wrap messages in `{"type":"prompt",...}`. The fake pi fixtures were updated to parse the JSON wrapper and use the `message` field as the prompt, so the existing suite still passes without changes. New `scripts/demo_phase4_smoke.py` exercises the full stack with real `pi` (sends a question, gets a streamed text response). 13 new unit tests for the translator + 6 sanity tests for the prompt files. |
| 4.4 | Real end-to-end build with live iteration | `backend/prompts/middleman.md`, `backend/prompts/worker.md`, `backend/seed_backend/flask_manager.py`, `backend/seed_backend/events.py` | Drove a real build (`Add a tiny /hello route`) with the local `opencode-go` / `deepseek-v4-flash` config. Observed the full chain: middle-man inspects state, emits a dispatch, worker edits `app.py` via the `edit` tool, verifies with `curl`, emits `<task:done summary="..."/>`, orchestrator broadcasts `complete` + `app_reload`. Three concrete issues found and fixed: (a) prompts hardcoded `/home/seed/app/` — replaced with `$SEED_APP_PATH` and threaded it through `pi_env_for_role` (env var, not argv); (b) the translator didn't handle `message_end` events, so the cheap deepseek model (which doesn't stream `text_delta` chunks) emitted the done marker only in `message_end` and the orchestrator's scan never saw it — added a `message_end` case that extracts the final text from `message.content` text blocks; (c) Flask wasn't in debug mode, so worker edits to `app.py` weren't picked up by the reloader and the worker had to manually `kill` + restart Flask (forbidden in production) — added `FLASK_DEBUG=1` to the subprocess env in `FlaskManager.start()`. After fixes, the worker verified the new route on the first `curl` attempt. The chat UI got `complete` + `app_reload` and the script exited cleanly. |

> **Embedded follow-up (2026-08-14):** verification is now mode-aware through
> `SEED_APP_URL`: the service supplies port 7778 for the host Flask subprocess
> and port 7777 for the Android WSGI mount. The remaining embedded mismatch is
> reload behavior: Python route edits do not become live in the in-process WSGI
> app until a safe runtime reload mechanism is implemented.

**Module shape after Phase 4 (so far):**
- `backend/prompts/middleman.md` (new) — role prompt for the intent agent.
- `backend/prompts/worker.md` (new) — role prompt for the builder agent.
- `events.py` — added `parse_task_done` (summary attribute), `translate_pi_line` (JSONL unwrapper), `PI_EVENT_*` / `PI_CMD_*` constants. The existing `TASK_DONE_MARKER` constant is still defined for back-compat (it's used as a substring anchor in the `parse_task_done` regex).
- `orchestrator.py` — `_read_middleman_loop` and `_read_worker_loop` use `translate_pi_line(line, role=...)`. New `_send_dispatch_to_worker` helper wraps the dispatch in an RPC `prompt` command.
- `pi_runner.py` — already had `env=` kwarg from Phase 3 (for the local `PI_CODING_AGENT_DIR`); no change.
- `fake_pi*.py` fixtures — parse the `{"type":"prompt","message":"..."}` wrapper; use `message` as the prompt.
- `tests/test_events.py` — 21 tests (8 for marker parser, 13 for translator).
- `tests/test_prompts.py` — 6 sanity tests (files exist, non-empty, mention key protocols).
- `scripts/demo_phase4_smoke.py` (new) — manual end-to-end with real `pi`.

## ✅ Phase 5 — Android shell (4 screens, nav, WebView) (9/9)

| # | Task | Files | Notes |
|---|---|---|---|
| 5.1 | Gradle init + blank MainActivity | `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradlew` + `gradle/wrapper/`, `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/seed/app/MainActivity.kt`, `android/app/src/main/java/com/seed/app/SeedApp.kt`, `android/app/src/main/res/values/{strings,colors,themes}.xml`, `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`, `android/app/src/main/res/drawable/ic_launcher_foreground.xml`, `android/README.md` | Modern AGP 8.5.0 + Kotlin 1.9.24 + Compose BOM 2024.06.00 (Material 3); minSdk 26, targetSdk 34. Pinned versions in the top-level `build.gradle.kts` with `apply false` so a fresh clone is deterministic. No `local.properties` in the repo — `ANDROID_HOME` env var (or auto-generated by Android Studio) is the source of truth, per the project `.gitignore`. At this milestone, `buildConfigField` exposed host-dev URLs `WEBAPP_DEV_URL=http://10.0.2.2:7778/` and `BACKEND_DEV_URL=http://10.0.2.2:7777/`. Phase 9 superseded these defaults: current active WebView/HTTP/WS clients use embedded loopback `http://127.0.0.1:7777/`, where Flask and FastAPI share one port. Verified: `./gradlew assembleDebug` produces a 15 MB `app-debug.apk`; AAPT confirms `package: com.seed.app`, `minSdkVersion: 26`, `targetSdkVersion: 34`, label "Seed". **Buildable without a device** — but 5.2+ need an emulator to verify the UI. |
| 5.2 | 4-section navigation skeleton | `android/app/src/main/java/com/seed/app/ui/nav/SeedNav.kt` (new), `android/app/src/main/java/com/seed/app/ui/{app,chat,shell,settings}/*Screen.kt` (new), `android/app/src/main/java/com/seed/app/MainActivity.kt` (modified), `android/app/src/main/res/values/strings.xml` (modified) | `SeedNav` is a `Scaffold` that owns a Material 3 `NavigationBar` (4 items: App / Chat / Shell / Settings) and a `NavHost` with matching routes. Standard bottom-nav tap idiom: `popUpTo` start + `saveState`/`restoreState` + `launchSingleTop`. The 4 `*Screen.kt` files are placeholders (centered title + subtitle string) — their real composables land in 5.3 (App/WebView), 5.4 (Chat), 5.5 (Shell), 5.6 (Settings). `MainActivity` now calls `SeedNav()`; the `SeedPlaceholderScreen` from 5.1 is gone. `R.string.tab_*` + `R.string.*_screen_title/subtitle` added. Auto-mirrored Chat icon (no deprecation warning; mirrors correctly in RTL). Verified: `assembleDebug` produces 16 MB `app-debug.apk`; lint clean on the new files; the backend suite still passes. **Visual verification was completed later in Phase 5.9.** |
| 5.3 | WebView in App screen | `android/app/src/main/java/com/seed/app/ui/app/AppScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/app/WebViewConfig.kt` (new), `android/app/src/main/res/xml/network_security_config.xml` (new), `android/app/src/test/java/com/seed/app/ui/app/WebViewConfigTest.kt` (new), `android/app/src/main/AndroidManifest.xml` (modified), `android/app/build.gradle.kts` (modified) | Replaces the 5.2 placeholder with `AndroidView` wrapping a `SwipeRefreshLayout` → `WebView` that loaded the milestone's host-dev `BuildConfig.WEBAPP_DEV_URL`; Phase 9 later changed it to `http://127.0.0.1:7777/`, where FastAPI mounts the Flask WSGI app. **Security model is two narrow allowlists, defence in depth:** (1) `res/xml/network_security_config.xml` permits cleartext HTTP only to 10.0.2.2 + 127.0.0.1 (everything else stays HTTPS-only at the platform level); (2) `WebViewConfig.isAllowedUrl` (in a new module, pure function over `java.net.URI`) is the second filter — `WebViewClient.shouldOverrideUrlLoading` returns `true` (= block) for any URL whose host isn't in `{10.0.2.2, 127.0.0.1}` or whose scheme isn't http(s). Uses the modern 3-arg `shouldOverrideUrlLoading(view, request)` overload (avoids the deprecation warning on the 2-arg one). Pull-to-refresh: `SwipeRefreshLayout` (from `androidx.swiperefreshlayout:1.1.0`) wraps the WebView and calls `webView.reload()` on swipe. Picked over Compose's `PullToRefreshBox` because that's only in material3 1.3+ and our Compose BOM (2024.06.00) ships material3 1.2.1. Lifecycle: `WebView` is `remember`-ed so it survives recomposition; `DisposableEffect(Unit)` calls `webView.destroy()` on dispose so the renderer thread doesn't leak. **Unit tested (JVM):** `WebViewConfigTest` — 13 tests covering scheme / host / userinfo / host-suffix / case / malformed-URL edge cases. Verified: `./gradlew assembleDebug` → BUILD SUCCESSFUL (16 MB APK); `./gradlew testDebugUnitTest` and the backend suite pass; `./gradlew lintDebug` → clean; `aapt dump` confirms `INTERNET` permission + `networkSecurityConfig` resource ref in the packaged APK. **Visual verification was completed later in Phase 5.9.** |
| 5.4 | Chat screen UI | `android/app/src/main/java/com/seed/app/ui/chat/ChatScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/chat/ChatMessage.kt` (new), `android/app/src/main/java/com/seed/app/ui/chat/ChatViewModel.kt` (new), `android/app/src/main/java/com/seed/app/ui/chat/MessageBubble.kt` (new), `android/app/src/test/java/com/seed/app/ui/chat/ChatViewModelTest.kt` (new) | Replaces the 5.2 chat placeholder with a real chat surface: a `LazyColumn` of `MessageBubble` rows driven by a `ChatViewModel` (`StateFlow<List<ChatMessage>>`), plus a bottom input bar (text field + send button). **Data model** — new `ChatMessage.kt` module: sealed class `ChatMessage` with three subclasses (`User` / `Agent` / `System`) so the Compose `when` in `MessageBubble` is exhaustively checked at compile time; `AgentRole` enum (MIDDLEMAN / WORKER) with `displayName` for card labels; `SystemEventKind` enum (COMPLETE / APP_RELOAD / ERROR) for orchestrator events. The companion-object `newId()` / `now()` helpers exist because Kotlin doesn't resolve private members of the enclosing class in a nested data class's default-parameter expression. **ChatViewModel** — `StateFlow<List<ChatMessage>>` (starts empty), `StateFlow<String> inputText`, `onInputChange(String)` passthrough, `send()` that trims the input, no-ops on blank, appends a User message, clears the input. **No backend wiring** — that's Phase 6.3. The public API (`messages` / `inputText` / `onInputChange` / `send`) is stable; Phase 6.3 only adds work inside `send()` and a flow-collector for the WebSocket events. **MessageBubble** — three private variants: `UserMessageBubble` (right-aligned, primary container fill, asymmetric corner radius so it "points" at the user), `AgentMessageCard` (full-width card, surfaceVariant fill, role label above body), `SystemMessageBanner` (full-width, colour keyed to `SystemEventKind`: tertiary / secondary / error container). **ChatScreen** — `LazyColumn` keyed on `message.id` (preserves row identity across list edits), `LaunchedEffect(messages.size)` auto-scrolls to bottom on new messages, `imePadding()` on the outer Column so the input bar is pushed above the soft keyboard, `KeyboardOptions(imeAction = ImeAction.Send)` + `KeyboardActions(onSend = send)` so the IME Send key submits, `testTag` semantics on list / input / send for future UI tests, `viewModel()` defaults to the NavBackStackEntry so the message list survives tab switches. **Unit tested (JVM):** `ChatViewModelTest` — 8 tests covering initial state, `onInputChange`, `send` (appends / clears / trims / no-op on empty / no-op on whitespace), and multi-message order. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (16 MB APK, no new deps); the Android JVM and backend suites pass; `./gradlew :app:lintDebug` → clean. **Visual verification was completed later in Phase 5.9.** |
| 5.5 | Shell screen UI | `android/app/src/main/java/com/seed/app/ui/shell/ShellScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/shell/OutputLine.kt` (new), `android/app/src/main/java/com/seed/app/ui/shell/ShellViewModel.kt` (new), `android/app/src/main/java/com/seed/app/ui/shell/OutputLineRow.kt` (new), `android/app/src/test/java/com/seed/app/ui/shell/ShellViewModelTest.kt` (new) | Replaces the 5.2 shell placeholder with a real shell surface: a top input row (text field + Run button + Cancel button) and a bottom `LazyColumn` of `OutputLineRow` rows driven by a `ShellViewModel` (`StateFlow<List<OutputLine>>`). **Data model** — new `OutputLine.kt` module: sealed class `OutputLine` with four subclasses (`Command` / `Stdout` / `Stderr` / `Exit`) so the Compose `when` in `OutputLineRow` is exhaustively checked at compile time. **ShellViewModel** — `StateFlow<List<OutputLine>>` (starts empty), `StateFlow<String> input`, `onInputChange(String)` passthrough, `submit()` that trims the input, no-ops on blank, appends a `Command` + a fake `Exit(0)`, clears the input. **No backend wiring** — that's Phase 6.4. The public API (`output` / `input` / `onInputChange` / `submit`) is stable; Phase 6.4 only adds work inside `submit()` and adds an `isExecuting` flow that drives the Cancel button. **OutputLineRow** — four private variants: `CommandLine` (monospaced two-tone `AnnotatedString`: `$` prompt in primary, command in onSurface), `PlainLine` (monospaced, onSurface — for Stdout), `StderrLine` (monospaced, error colour), `ExitLine` (monospaced, muted `onSurfaceVariant`, renders `[exit N]`). All rows are 13 sp monospaced so columns line up the way they would in a real terminal. **ShellScreen** — input at TOP, output at BOTTOM (the plan calls for this layout, vs. the chat screen's input-at-bottom: the shell is a "form + log" pattern, not conversational, so the input is always visible and easy to reach with one hand). `LazyColumn` keyed on `OutputLine.id`, `LaunchedEffect(output.size)` auto-scrolls to the bottom on new lines, `imePadding()` on the outer Column, `KeyboardOptions(imeAction = ImeAction.Send)` + `KeyboardActions(onSend = submit)` so the IME Send key submits. **Cancel button** is rendered (so the layout is stable when Phase 6.4 enables it) but is permanently `enabled = false` in 5.5 — there is no "command running" state. Phase 6.4 will add the `isExecuting` flow and `cancel()` method. `testTag` semantics on list / input / run / cancel for future UI tests. **Unit tested (JVM):** `ShellViewModelTest` — 8 tests covering initial state, `onInputChange`, `submit` (appends Command+Exit / clears input / trims / no-op on empty / no-op on whitespace-only), and multi-submit order. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (16 MB APK, no new deps); the Android JVM and backend suites pass; `./gradlew :app:lintDebug` → clean. **Visual verification was completed later in Phase 5.9.** |
| 5.6 | Settings screen UI | `android/app/src/main/java/com/seed/app/ui/settings/SettingsScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/settings/SettingsForm.kt` (new), `android/app/src/main/java/com/seed/app/ui/settings/SettingsViewModel.kt` (new), `android/app/src/main/res/values/strings.xml` (modified), `android/app/src/test/java/com/seed/app/ui/settings/SettingsViewModelTest.kt` (new) | Replaces the 5.2 settings placeholder with a real settings form: a scrollable `Column` with a provider dropdown, model text field, secure API key field, two numeric port fields, a log-level dropdown, and a Save button — all bound to a `SettingsViewModel` (`StateFlow<SettingsForm>`). **Data model** — new `SettingsForm.kt` module: `data class SettingsForm` with six fields (provider, model, apiKey, backendPort, webappPort, logLevel) with `DEFAULTS` matching the dev ports (7777/7778) and `KNOWN_PROVIDERS` (openai / anthropic / local) the provider dropdown suggests; `enum class LogLevel` (DEBUG / INFO / WARNING / ERROR) with `displayName` for the dropdown label and `ordinal` for the future in-app logger filter (Phase 7+). **SettingsViewModel** — `StateFlow<SettingsForm>` (starts at DEFAULTS), `StateFlow<SettingsForm?> lastSaved` (null until Save), six typed setters (`onProviderChange(String)` / `onModelChange(String)` / `onApiKeyChange(String)` / `onBackendPortChange(Int)` / `onWebappPortChange(Int)` / `onLogLevelChange(LogLevel)`) that each do a single `copy(...)` so the other fields are preserved, and `save()` that records the current form into `lastSaved` (in-memory). **No persistence yet** — that's Phase 5.7. The public API (`form` / `lastSaved` / six onXChange setters / `save`) is stable; Phase 5.7 only rewires `save()` to call `SettingsRepo` and adds a constructor-injected repo. **SettingsScreen** — `Column` with `verticalScroll` so the form scrolls on small screens; `SettingsHeader` (title + `StatusPill` on the right that flips between "Modified" `errorContainer` and "Saved" `tertiaryContainer` based on `form == lastSaved`); `ProviderDropdown` (M3 `ExposedDropdownMenuBox`, free-form — KNOWN_PROVIDERS is suggestion, not closed set); `OutlinedTextField` for model; `OutlinedTextField` for apiKey with `PasswordVisualTransformation` + `KeyboardType.Password` so the key is hidden on screen; two `PortField`s side by side in a `Row` with `weight(1f)` each, parsing to `Int` on each keystroke (`toIntOrNull() ?: 0` silently drops mid-typed garbage); `LogLevelDropdown` (M3 `ExposedDropdownMenuBox` over the closed `LogLevel` enum, read-only field); Save `Button` at the bottom. `testTag` semantics on every field for future UI tests. `strings.xml` gained 9 new strings (six field labels, save action, modified / saved status labels). **Unit tested (JVM):** `SettingsViewModelTest` — 9 tests covering initial state, each of the six onXChange setters, multi-field changes accumulate, and `save()` records the current form as `lastSaved`. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (16 MB APK, no new deps); the Android JVM and backend suites pass; `./gradlew :app:lintDebug` → clean. **Visual verification was completed later in Phase 5.9.** |
| 5.7 | DataStore settings repo (unit-testable on JVM) | `android/app/src/main/java/com/seed/app/data/SettingsRepo.kt` (new), `android/app/src/main/java/com/seed/app/data/AndroidSettingsRepo.kt` (new), `android/app/src/main/java/com/seed/app/ui/settings/SettingsViewModel.kt` (modified), `android/app/src/test/java/com/seed/app/data/SettingsRepoTest.kt` (new), `android/app/src/test/java/com/seed/app/ui/settings/SettingsViewModelTest.kt` (modified), `android/app/build.gradle.kts` (modified) | Wires the Phase 5.6 form to a real persistence layer. **Repo abstraction** — new `data/` package: `SettingsRepo` interface with two `suspend` methods (`load(): SettingsForm?` returns null on a fresh install; `save(form: SettingsForm)` persists), plus a stateless `SettingsRepo.InMemory` companion for tests and previews. **Production impl** — `AndroidSettingsRepo` (not unit-testable on plain JVM — it needs a `Context` and the Android Keystore for `EncryptedSharedPreferences`): two-store split. `DataStore-Preferences` holds the non-secret fields (provider, model, backendPort, webappPort, logLevel) in a plain-text `settings.preferences_pb` file under `filesDir/datastore/`. `EncryptedSharedPreferences` (AES-256-SIV keys + AES-256-GCM values, master key in the Android Keystore via `MasterKey.KeyScheme.AES256_GCM`) holds only the API key in a separate `secure_settings.xml` file. The two-store split is the cleanest way to keep the API key encrypted at rest without writing a custom DataStore serializer. "Saved" is signalled by the presence of the `provider` key in DataStore — a fresh install returns null from `load()`, a partial-write (e.g. crash mid-save) is treated as not-saved. **ViewModel wiring** — `SettingsViewModel` constructor now takes a `SettingsRepo` (default `SettingsRepo.InMemory` for the no-arg convenience overload); on `init` a `viewModelScope` coroutine calls `repo.load()` and, if non-null, hydrates both `_form` and `_lastSaved` from the persisted form (so the "Saved" status pill is correct after a restart, but a fresh install keeps `lastSaved` null so the pill correctly shows "Modified"). `save()` calls `repo.save(currentForm)` then updates `_lastSaved` (was just in-memory before). Public API (`form` / `lastSaved` / six onXChange setters / `save`) is the same shape as 5.6 — `SettingsScreen.kt` doesn't need to change. **Deps** — `androidx.datastore:datastore-preferences:1.0.0` (DataStore) + `androidx.security:security-crypto:1.1.0` (EncryptedSharedPreferences; latest stable as of March 2024). **Unit tested (JVM):** `SettingsRepoTest` — 3 tests for the `InMemory` companion (singleton identity, `load` returns null, `save` is a no-op). `SettingsViewModelTest` gained 5 wiring tests using a private `RecordingSettingsRepo` (init hydrates from repo, init leaves `lastSaved` null on fresh install, `save` calls `repo.save`, `save` updates `lastSaved` but not `form`, `InMemory` default still behaves like 5.6). The test class installs an `UnconfinedTestDispatcher` as `Dispatchers.Main` in `@Before` so `viewModelScope` can launch its hydration coroutine on a real dispatcher; the Unconfined dispatcher runs eagerly, so no `advanceUntilIdle()` is needed. **Why a narrow interface (not a `Flow<SettingsForm>`):** the ViewModel only needs a one-shot hydration on init and a write on save. A continuous stream would either re-emit on every save (fighting the in-memory form) or need a `null` sentinel for "never been saved", complicating the type. Suspend functions let the DataStore impl do its async IO cleanly and the `InMemory` impl stay trivially sync. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (17.9 MB APK, +1.8 MB from DataStore + security-crypto + Tink); the Android JVM and backend suites pass; `./gradlew :app:lintDebug` reports no new warnings from this task. **Visual verification was completed later in Phase 5.9.** |
| 5.8 | Theme + system bar styling | `android/app/src/main/java/com/seed/app/ui/theme/SeedTheme.kt` (new), `android/app/src/main/java/com/seed/app/MainActivity.kt` (modified), `android/app/src/main/res/values/themes.xml` (modified) | Replaces the bare `MaterialTheme {}` from 5.2–5.7 with a real Seed theme. **SeedTheme** — `@Composable fun SeedTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit)` wrapping `MaterialTheme` with the "seed green" primary family (Material Green 800/200/900) for both light and dark schemes, plus a `SideEffect` that flips `WindowInsetsController.isAppearanceLightStatusBars` and `isAppearanceLightNavigationBars` to match the active scheme (so the system-bar icons stay legible on the translucent bars that `enableEdgeToEdge()` gives us). The `SideEffect` is guarded by `!view.isInEditMode` so Compose `@Preview` composables still render. M3 secondary / tertiary / error / surface tokens stay at the framework defaults for v0.1 (we only override the primary family). **MainActivity** — now calls `SeedTheme { SeedNav() }`. **themes.xml** — `Theme.Seed` now sets `android:windowBackground` to `@color/seed_green` so the few-frame gap between Activity start and the first Compose paint flashes green (the brand colour) instead of the framework default white. The platform-level theme still inherits from `android:Theme.DeviceDefault` (no Material Components library required). The colour values in `SeedTheme.kt` mirror the `seed_green` / `seed_green_dark` / `seed_green_container` / `seed_on_green_container` values in `res/values/colors.xml`; the XML is the source of truth for the launcher icon background + the platform window background, the Compose values are the source of truth for the in-app palette. **No unit tests** — theme is visual styling that's hard to assert on JVM (no rendering); the build + lint + existing-test pass is the only automated check we can do. Visual verification (look at the bars in light + dark mode, look at the green primary in the four screens) is Phase 5.9's job. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (17.9 MB APK, no size change — no new deps); the Android JVM and backend suites still pass; lint reports no new warnings from this task; `aapt dump` confirms `Theme.Seed` in the packaged APK has `windowBackground` → `@color/seed_green`. |

**Android SDK setup** (one-time, not in repo):
- Command-line tools installed at `~/android-sdk/cmdline-tools/latest/`
- `sdkmanager` installed: `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
- KVM is available (`/dev/kvm`) and was used for Phase 5.9 visual verification
- Gradle 8.7 bootstrap'd the wrapper; `gradle-wrapper.jar` + `gradlew` are committed

**Current status:**
- Phase 5.9 visual verification is complete; the emulator results are recorded below.
- Phase 6 backend wiring is complete.
- Phase 7 now builds arm64 or x86_64 runtime bundles, packages the selected four-library native PRoot bundle through `jniLibs`, and extracts only rootfs/version data from assets.
- Native embedded-runtime startup was accepted on the x86_64 emulator on 2026-08-12; the agent loop remained blocked by `fork(2)`.

**Verification:**
- ✅ `assembleDebug` produces a runtime-bearing debug APK (~370.6 MB decimal / 353.4 MiB for the current x86_64 build)
- ✅ AAPT confirms manifest, resources, and version codes
- ✅ `lintDebug` completes with 0 errors (27 warnings on 2026-08-13)
- ✅ Backend and webapp pass separately; Android JVM suite passes 173/173
- ✅ Current instrumentation suite (2 classes / 6 methods) compiles
- ✅ Native PRoot/rootfs, uvicorn, WebView, and Shell startup were accepted on the x86_64 emulator on 2026-08-12
- ❌ The embedded `pi` processes still cannot start because `PiRunner` requires `fork(2)`

## ✅ Phase 6 — Android ↔ backend wiring (5/5)

| # | Task | Files | Notes |
|---|---|---|---|
| 6.1 | Retrofit + OkHttp + Moshi backend client | `android/app/src/main/java/com/seed/app/data/BackendApi.kt` (new), `android/app/src/main/java/com/seed/app/data/ApiModule.kt` (new), `android/app/src/test/java/com/seed/app/data/BackendApiTest.kt` (new), `android/app/build.gradle.kts` (modified) | Adds the HTTP layer the Android app uses to talk to the FastAPI orchestrator. Three endpoints: `GET /health`, `POST /shell/exec`, `PUT /config`. Snake-case JSON fields (`exit_code`, `api_key`) are mapped to camelCase Kotlin properties via Moshi's `@Json(name=...)`. Manual DI: `ApiModule.default` lazy-binds to `BuildConfig.BACKEND_DEV_URL`; `ApiModule.forTesting(url)` builds a fresh Retrofit with debug logging forced on. No Hilt — the app is too small for the annotation-processor tax. **Deps:** retrofit 2.11.0, converter-moshi 2.11.0, okhttp 4.12.0 (+ logging-interceptor), moshi 1.15.1 + moshi-kotlin + kotlin-reflect 1.9.24, testImplementation mockwebserver 4.12.0. **APK:** 17.9 MB → 18.8 MB (+890 KB). **9 unit tests** via MockWebServer — pin the wire format (snake_case ↔ camelCase), the suspend boundary, the 422 path, and the body shape the backend's Pydantic models expect. |
| 6.2 | WebSocket chat client | `android/app/src/main/java/com/seed/app/data/ChatEvent.kt` (new), `android/app/src/main/java/com/seed/app/data/ChatWebSocket.kt` (new), `android/app/src/test/java/com/seed/app/data/ChatWebSocketTest.kt` (new) | OkHttp `WebSocket` (no extra dep) wrapped in a small lifecycle class. Public surface: `connect()` (idempotent, starts a long-running coroutine), `disconnect()` (cancels the loop + closes the WS cleanly with code 1000), `send(text)` (JSON-escapes via Moshi, returns `false` if not connected), `events: SharedFlow<ChatEvent>` (0 replay, 64-slot buffer, `DROP_OLDEST`, `tryEmit` for order-preservation across concurrent `onMessage` calls), `state: StateFlow<ConnectionState>`. **Reconnect with backoff:** `ReconnectBackoff` is 1s/2s/4s/8s/16s/30s (cap), no jitter, reset on successful `onOpen`. **ChatEvent** sealed class: MiddlemanLine / WorkerLine / Complete / AppReload / Error — gives the ChatViewModel's `when` a compile-time exhaustiveness check. **12 unit tests** + 3 `ReconnectBackoffTest` tests via MockWebServer's `WebSocketListener` (the 4.12.0 API — no `WebSocketHandler` class in 4.x). The `tearDown` catches `MockWebServer.shutdown()`'s "Gave up waiting for queue to shut down" — a known interop quirk with OkHttp 4.x WebSocket; the test body has already passed by then. |
| 6.3 | Wire Chat screen | `android/app/src/main/java/com/seed/app/data/ChatTransport.kt` (new), `android/app/src/main/java/com/seed/app/data/ChatWebSocket.kt` (modified), `android/app/src/main/java/com/seed/app/ui/chat/ChatViewModel.kt` (modified), `android/app/src/test/java/com/seed/app/ui/chat/ChatViewModelTest.kt` (modified) | `ChatTransport` interface (connect/send/events/close) is the testability seam — `ChatWebSocket` implements it; tests provide a `FakeChatTransport` that captures outbound `send` calls and emits canned `ChatEvent`s via `MutableSharedFlow.tryEmit`. The ViewModel's constructor takes `chat: ChatTransport = ChatWebSocket(BuildConfig.BACKEND_DEV_URL)`. `init` calls `chat.connect()` and launches a `viewModelScope` collector that translates each `ChatEvent` to a `ChatMessage` (MiddlemanLine → `Agent(MIDDLEMAN)`, WorkerLine → `Agent(WORKER)`, Complete → `System(COMPLETE)`, AppReload → `System(APP_RELOAD)`, Error → `System(ERROR)`). `send()` now also calls `chat.send(text)` after appending the local User bubble. `onCleared` calls `chat.close()` so the connection loop and scope don't outlive the screen. The Compose `ChatScreen.kt` doesn't change — the public API (`messages`, `inputText`, `onInputChange`, `send`) is the same shape as Phase 5.4. **10 new unit tests** (8 pre-existing local-only tests, updated to pass `FakeChatTransport`); the ChatViewModel test suite passes. `onCleared` coverage is omitted — `ViewModel.onCleared` is `protected` and the JVM unit test classpath can't easily trigger it; the close logic is exercised in `ChatWebSocketTest`. |
| 6.4 | Wire Shell screen | `android/app/src/main/java/com/seed/app/ui/shell/ShellViewModel.kt` (modified), `android/app/src/main/java/com/seed/app/ui/shell/ShellScreen.kt` (modified), `android/app/src/test/java/com/seed/app/ui/shell/ShellViewModelTest.kt` (modified) | Constructor takes `backend: BackendApi = ApiModule.default`. `submit()` launches a `viewModelScope` coroutine that calls `POST /shell/exec`, then appends `Stdout` (if stdout non-empty) + `Stderr` (if stderr non-empty) + `Exit(exitCode)` lines. Network/HTTP failures surface as a sentinel `Exit(-1)` so the user sees something went wrong without crashing. New `isExecuting: StateFlow<Boolean>` (true while a call is in flight) drives the Shell screen's Cancel button. New `cancel()` is a no-op stub for v0.1 — the backend has no cancel endpoint, `isExecuting` remains true, and the HTTP call runs until it returns or times out. A future task (Phase 10) will add a real cancel. Guard against concurrent submits: a second `submit` while a call is in flight is a no-op (the Run button is also visually disabled in the screen via the same `isExecuting` flow). The Compose `ShellScreen.kt` reads `isExecuting` and passes it to the input bar; Run button enabled = `value.isNotBlank() && !isExecuting`. **7 new unit tests** + an `in-flight` test that holds the call open with a `CompletableDeferred`; the ShellViewModel test suite passes. `FakeBackendApi` (in the test file) captures every `shellExec` call; `health()` and `putConfig()` are `TODO()` because the Shell screen doesn't use them. |
| 6.5 | Wire Settings screen + `PUT /config` route | `backend/seed_backend/service.py` (modified), `backend/tests/test_config_route.py` (new), `android/app/src/main/java/com/seed/app/data/ConfigSync.kt` (new), `android/app/src/main/java/com/seed/app/ui/settings/SettingsViewModel.kt` (modified), `android/app/src/test/java/com/seed/app/data/ConfigSyncTest.kt` (new), `android/app/src/test/java/com/seed/app/ui/settings/SettingsViewModelTest.kt` (modified) | **Backend:** new `ConfigPayload` (Pydantic, mirrors the Android `ConfigRequest`), new `ConfigResponse` (`{ok: bool}`), new `PUT /config` route that writes the payload via `Config.save(DEFAULT_CONFIG_PATH)` where `DEFAULT_CONFIG_PATH = Path("config.json")` (the uvicorn CWD). `backend/scripts/dev.sh` now changes into `backend/`, so host-dev writes resolve deterministically to `backend/config.json`; other launch methods remain CWD-relative. `min_length=1` on `provider` and `model`; `api_key` accepts the empty string (a fresh install's default). **5 backend tests** pin the wire format, the overwrite-not-append behaviour, the 422-on-empty-provider path, and non-default port handling. **Android:** new `ConfigSync` class (open, so tests can subclass) bridges `SettingsForm` → `ConfigRequest` and PUTs to the backend. Constructor takes `BackendApi`; `sync(form)` returns true on `ok=true`, false on any failure (network, HTTP 4xx/5xx, `ok=false`) — no exceptions leak. The `toRequest` mapping drops `logLevel` (the orchestrator has no log level concept yet; Phase 7+ will wire it). The SettingsViewModel's `save()` now: `repo.save(current)` → `sync.sync(current)` → `_lastSaved.value = current` — best-effort; a sync failure doesn't roll back the local save or block the status-pill flip (the local save is authoritative, the backend sync is a sink). **3 new SettingsViewModel tests** + **5 new ConfigSync tests** (the wire format — snake_case `api_key`, nested `ports`, the `logLevel` drop). The Android and backend suites passed after this task. |

**Module shape after Phase 6:**
- `data/BackendApi.kt` — Retrofit interface (`/health`, `/shell/exec`, `/config`) + DTOs (HealthResponse, ShellExecRequest, ShellExecResponse, ConfigRequest, ConfigPorts, ConfigResponse).
- `data/ApiModule.kt` — manual-DI factory (`default` lazy-bound to `BuildConfig.BACKEND_DEV_URL`; `forTesting(url)` for tests).
- `data/ChatTransport.kt` — narrow interface (connect/send/events/close) the ChatViewModel needs.
- `data/ChatWebSocket.kt` — OkHttp-based WebSocket client with reconnect + backoff. Implements `ChatTransport`.
- `data/ChatEvent.kt` — sealed class: MiddlemanLine / WorkerLine / Complete / AppReload / Error.
- `data/ConfigSync.kt` — SettingsForm → ConfigRequest mapper + PUT /config caller. `open` so tests can subclass.
- `ui/chat/ChatViewModel.kt` — wired to ChatTransport; `init` connects + collects events, `send` forwards the prompt, `onCleared` closes.
- `ui/shell/ShellViewModel.kt` — wired to BackendApi; `submit` calls `shellExec` + appends Stdout/Stderr/Exit, `isExecuting` drives the Cancel button, `cancel` is a v0.1 no-op.
- `ui/shell/ShellScreen.kt` — reads `isExecuting`, passes to input bar; Run button enabled = non-blank AND not executing; Cancel button enabled = executing.
- `ui/settings/SettingsViewModel.kt` — wired to SettingsRepo + ConfigSync; `save` does local + backend in order; public API unchanged from 5.7.
- `seed_backend/service.py` — new `ConfigPayload` / `ConfigResponse` / `PUT /config` route + `DEFAULT_CONFIG_PATH`.

**APK size:** 17.9 MB → 18.9 MB (+1 MB for Retrofit/OkHttp/Moshi/kotlin-reflect; this phase added WebSocket + chat-event serialization, no size delta vs. 6.4). All runtime deps in the 4.12.0 / 1.15.1 / 2.11.0 line; kotlin-reflect is the heaviest at ~3 MB and pays for the Moshi KotlinJsonAdapterFactory (the KSP-codegen alternative would be lighter but needs a build plugin and is overkill for ~3 DTOs).

## ✅ Phase 7 — Native proot packaging + rootfs extraction (5/5)

| # | Task | Files | Notes |
|---|---|---|---|
| 7.1 | Architecture-aware builder + generated layout | `scripts/{build-runtime.sh,runtime-target.sh}`, `android/app/src/main/jniLibs/`, `android/app/src/main/assets/linux/`, `docs/build-runtime.md` | The builder supports arm64 and x86_64 Docker targets. It publishes the selected four-file native bundle (`libproot.so`, `libproot-loader.so`, `libtalloc.so`, `libandroid-shmem.so`) under the matching `jniLibs` ABI, removes the opposite generated ABI after successful validation, and publishes the matching source `rootfs.tar.gz` plus tracked `seed_version.json`. Generated native/rootfs files are Git-ignored. |
| 7.2 | Build and validate both target layouts | (verification/tooling) | Architecture mapping and publication checks cover arm64 and x86_64. Every selected native library must be a matching 64-bit ELF; the Alpine archive checksum, Docker image architecture, and required Python, Node, pi, backend, and webapp runtime contents are validated before publication. The x86_64 runtime was accepted on the emulator on 2026-08-12; arm64 still requires a matching generated build and physical-device verification. |
| 7.3 | Rootfs data extraction | `app/src/main/java/com/seed/app/runtime/{ExtractionProgress,AssetSource,RuntimeExtractor}.kt` | `RuntimeExtractor` consumes data entries only. It expands the merged `rootfs.tar` directly into `filesDir/linux/rootfs`, copies the marker, preserves symlinks and executable modes, materializes hard links as independent copies because Android SELinux denies filesystem hard links in private app data, rejects traversal, and cleans partial extraction after failure. It never copies or applies `chmod` to proot. JVM tests cover the extraction contract. |
| 7.4 | `RootfsVersion` + data-only `AndroidAssetSource` | `app/src/main/java/com/seed/app/runtime/{RootfsVersion,AndroidAssetSource}.kt` | `RootfsVersion(seedVersion, buildId)` parses the marker. `AndroidAssetSource` selects only merged `rootfs.tar` and `seed_version.json`; legacy asset proot entries are ignored because proot is installed through the selected `jniLibs` ABI. JVM tests cover marker parsing and the asset boundary. |
| 7.5 | Boot controller + extraction UI + MainActivity wiring | `app/src/main/java/com/seed/app/runtime/{BootState,BootController,ExtractionScreen}.kt`, `app/src/main/java/com/seed/app/MainActivity.kt` | `BootController` owns a `StateFlow<BootState>` (`NeedsExtraction` → `Extracting(progress)` → `Ready`), compares `filesDir/linux/.version` to the asset version, drives the extraction flow, writes `.version` on success. Extraction is serialized across activity recreation. JVM tests cover the controller. |

**Module shape after Phase 7:** `com.seed.app.runtime` owns the data-only asset boundary and rootfs installation. Proot is resolved separately from `applicationInfo.nativeLibraryDir/libproot.so`; it is never placed under writable app storage. The Android JVM suite passes.

**APK packaging:** the generated source file is gzip-compressed `assets/linux/rootfs.tar.gz`, but AGP expands it during asset merging and packages it as `assets/linux/rootfs.tar`. That merged TAR is listed in `noCompress` so `AssetManager.openFd` can stream it. The APK therefore carries the expanded TAR size, not a compressed runtime rootfs; splitting it into a separate delivery artifact remains a future optimization.

**Build cost:** first `./scripts/build-runtime.sh` takes 5–15 min (Docker image pull + Alpine apk + npm install of pi). Re-runs are fast (~30 s) thanks to Docker layer caching and a deterministic tar pipeline.

## ✅ Phase 8 — Foreground service (run the embedded runtime)

**Full design:** [`docs/plans/2026-07-03-embedded-runtime.md`](docs/plans/2026-07-03-embedded-runtime.md).
Phase 7 built the *extraction* half of the embedded runtime; this phase
builds the *process supervision* half — the foreground service that
spawns `proot` and keeps it alive in a foreground service even when the
activity is backgrounded.

| # | Task | Files | Notes |
|---|---|---|---|
| 8.0 | Native proot + asset rootfs installation (Phase 7 carryover) | `android/app/src/main/jniLibs/{arm64-v8a,x86_64}/` (selected ABI's four native libraries), `android/app/src/main/assets/linux/{rootfs.tar.gz,seed_version.json}`, `runtime/{NativeProot,RuntimeExtractor}.kt`, related tests | ✅ Runtime generation publishes PRoot, its loader, and required shared libraries through the selected native-library ABI and keeps rootfs/version as assets. AGP expands source `rootfs.tar.gz` to merged `rootfs.tar`, stored with `noCompress`; extraction streams it into `filesDir/linux/rootfs/` with Commons Compress. It preserves supported files, symlinks, and executable modes, materializes hard links as independent copies because Android SELinux denies filesystem hard links in private app data, rejects traversal, observes cancellation, and removes partial rootfs trees on failure. JVM and shell suites cover these boundaries. |
| 8.1 | `ProotRunner` | `app/src/main/java/com/seed/app/runtime/ProotRunner.kt`, `app/src/test/java/com/seed/app/runtime/ProotRunnerTest.kt` | ✅ Spawns `proot -r filesDir/linux/rootfs -b /dev -b /proc --kill-on-exit /bin/sh -c "cd /home/seed/backend && exec uvicorn seed_backend.service:app --host 127.0.0.1 --port 7777"` via an injected `ProcessFactory`; exposes `isAlive`, `destroy()`, and separate stdout/stderr flows. Their 64-capacity channels buffer early output and apply backpressure once full. `destroy()` is idempotent and non-blocking: SIGTERM is followed by a daemon-thread five-second wait and `destroyForcibly()` escalation. **11 JVM tests currently** (9 at the initial Phase 8 task). |
| 8.2 | `HealthMonitor` | `app/src/main/java/com/seed/app/runtime/HealthMonitor.kt`, `app/src/test/java/com/seed/app/runtime/HealthMonitorTest.kt` | ✅ Cold `Flow<HealthState>` emits `Unknown`, then polls `BackendApi.health()` every 500 ms for up to 60 attempts. Each request has a 500 ms timeout; failed probes retry, exhaustion emits the final error as `Unhealthy`, and cancellation propagates. Any successful backend response is `Healthy(flask)`, including `flask="down"`. **8 JVM tests** cover immediate success, Flask-down readiness, fixed retry cadence, exhausted attempts, request timeout, and cancellation during both a probe and retry delay. |
| 8.3 | `RuntimeService` (foreground) | `app/src/main/java/com/seed/app/runtime/{RuntimeService,RuntimeBinder}.kt` (new), `data/ApiModule.kt` (modified) | ✅ `RuntimeService` promotes itself immediately, resolves a `NativeProotInstallation` (executable, loader, libtalloc, and libandroid-shmem) from `applicationInfo.nativeLibraryDir`, starts it with `HOME`, `LANG`, `PATH`, `TERM`, `PROOT_TMP_DIR`, `PROOT_LOADER`, and `LD_LIBRARY_PATH`, logs both output streams, and polls the loopback backend through `ApiModule.embedded`. `RuntimeBinder` exposes health, process liveness, and stop. `onDestroy` terminates proot and cancels the service scope. The x86_64 service wiring, embedded uvicorn, and health polling were accepted on the emulator on 2026-08-12. |
| 8.4 | Manifest + permissions + notification channel | `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/seed/app/SeedApp.kt`, `app/src/main/res/drawable/ic_stat_seed.xml`, `res/values/strings.xml` | ✅ Declares `FOREGROUND_SERVICE`, Android 14's `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`, and the non-exported `dataSync` service. `SeedApp` creates the low-importance `seed_runtime` channel on API 26+, and the ongoing service notification uses a monochrome vector icon plus a `MainActivity` content intent. The API 33 notification prompt remains Phase 9 activity wiring. |

**Module shape after Phase 8:** the runtime package owns installation (`RuntimeExtractor`), installed-native lookup (`NativeProot`), process launch/termination (`ProotRunner`), readiness polling (`HealthMonitor`), and Android lifetime (`RuntimeService` + `RuntimeBinder`). The Android JVM suite passes, and the x86_64 service/runtime path was accepted on the emulator on 2026-08-12.

## ✅ Phase 9 — First-run runtime startup gate (4/4)

| # | Task | Files | Notes |
|---|---|---|---|
| 9.1 | Startup state resolver | `runtime/RuntimeStartup.kt`, `RuntimeStartupTest.kt` | ✅ Keeps extraction-owned `BootState` separate from service-owned `HealthState`. A pure resolver maps the pair to extraction UI, runtime UI, or `SeedNav`; a single-fire gate starts the service only after extraction is ready. This supersedes the older duplicate `BootState.Starting` / `RuntimeError` proposal. |
| 9.2 | Runtime startup + retry UI | `runtime/StartRuntimeScreen.kt`, `androidTest/.../StartRuntimeScreenTest.kt`, `res/values/strings.xml` | ✅ Shows polling progress and attempt count, or an error banner and Retry action. The current instrumentation suite compiles; it was not run on a device during the 2026-08-13 verification. |
| 9.3 | Service lifecycle wiring + retry | `MainActivity.kt`, `runtime/{RuntimeSupervisor,RuntimeService,RuntimeBinder}.kt` | ✅ `MainActivity` starts and binds the foreground service after extraction, mirrors binder health, requests Android 13+ notification permission once, gates navigation until healthy, retains the service in the background, and unbinds on destroy. Retry re-polls a live process or replaces a dead one. Extraction is single-flight across activity recreation. |
| 9.4 | Embedded endpoint defaults + host persistence | `app/build.gradle.kts`, `data/{ApiModule,AndroidSettingsRepo}.kt`, `ui/{app,settings}/*`, related tests | ✅ Active HTTP, WebSocket, and WebView clients use `127.0.0.1:7777`; Flask is WSGI-mounted on the FastAPI port in the embedded runtime. Cleartext/navigation allowlists retain loopback plus `10.0.2.2`. `SettingsForm.host` persists `127.0.0.1`; its legacy `webappPort=7778` field and other saved endpoint values do not currently rebuild clients or reconfigure/restart the backend. That operational wiring and host UI remain Phase 10 work. |

**Module shape after Phase 9:** `RuntimeSupervisor` owns retryable process/health startup, `RuntimeStartup` owns pure UI gating, and `MainActivity` is the Android lifecycle adapter. The Android JVM suite passes; the current 2-class / 6-method instrumentation suite compiles but was not run during the latest verification.

## ⬜ Phase 10 — Embedded agent loop + end-to-end readiness

The first item is a release blocker; the remaining items turn the working
runtime shell into a safe, recoverable product flow.

1. **Replace the fork-based `PiRunner` process model.** Android proot returns
   ENOSYS for `fork(2)`, so neither embedded `pi` process currently starts.
   Preserve RPC streaming, process-group termination, tool filtering, and tests
   using an Android-compatible launcher.
2. **Make worker edits take effect in embedded mode.** Worker verification now
   uses the mode-aware `SEED_APP_URL` (completed 2026-08-14), but the in-process
   WSGI fallback has no Flask reloader. Define a safe app reload/restart
   mechanism, then make `app_reload` refresh the App WebView rather than only
   adding a Chat banner.
3. **Secure the loopback control plane.** The middle-man now has a matching
   pi CLI tool allowlist and runtime event filter (completed 2026-08-14).
   Remaining work: authenticate HTTP/WS requests, verify backend identity,
   separate or authenticate mutable web content versus privileged control
   routes, protect API-key transfer/storage, and constrain worker mutation to
   the app workspace.
4. **Finish Shell and Chat behavior.** Android now renders a distinct warning
   when the backend reports truncated shell output (completed 2026-08-14).
   Remaining work: add a real backend cancellation protocol, wire Android Cancel
   to it, surface connection/sync failures, avoid silently dropping offline
   sends, and bound or persist long histories.
5. **Make Settings operational.** Load saved provider/model/key/ports at backend
   startup, rebuild clients or restart services when endpoints change, expose
   the persisted host where appropriate, and remove or migrate the legacy
   two-port fields.
6. **Harden runtime recovery.** Detect process death after initial health,
   handle bind timeouts and extraction failures, add explicit stop/restart/wipe
   controls, and enable bounded crash supervision.
7. **Run the product demo and release checks.** Complete the "Add a habit
   tracker" standalone-APK demo, run instrumentation on x86_64 and arm64,
   resolve lint/release-signing/licensing items, and add CI plus static checks.

Deferred distribution/UI work from the embedded-runtime design includes the
notification/icon polish, App-bar health status, and moving the ~350 MB
uncompressed rootfs out of the base APK (for example via an appropriate modern
Android asset-delivery mechanism rather than assuming `.obb`).

---

## Phase 5.9 — visual verification (carried forward + completed)

**Status: ✅ verified on the emulator against a host-side dev backend
(2026-07-03).** This historical Phase 5/6 UI verification is distinct from the
embedded-runtime bring-up on 2026-08-12. The referenced screenshots were local
scratch artifacts and are not tracked in the repository.

**What was verified:**

| # | Verification | Result |
|---|---|---|
| 5.9.1 | App tab loads the Flask webapp at `10.0.2.2:7778` | ✅ "Hello, what should I become?" + "ready (ping ok)" rendered. Proves the backend is reachable from the emulator and the WebView + network-security-config allowlist is correct. |
| 5.9.2 | Seed green theme + dark-mode bar icons | ✅ Status bar + system bar use the Seed green; icons are light on dark in dark mode (the SideEffect in `SeedTheme` flips the `isAppearanceLight*` flags). |
| 5.9.3 | 4-tab bottom nav | ✅ App / Chat / Shell / Settings; selected tab gets the purple pill indicator. |
| 5.9.4 | Chat send → WS connect → fake-pi response stream | ✅ User message bubble appears; worker emits 3 progress events + `<task:done/>`; chat UI shows Worker cards (purple surfaceVariant) + "Task complete" banner (pink errorContainer) + "App reloading" banner. All 5 ChatEvent → ChatMessage translations working. |
| 5.9.5 | Shell submit → POST /shell/exec → render | ✅ `$ echo hi from the shell` → `hi from the shell` → `[exit 0]` rendered with monospaced font + green `$` prompt + muted exit. Backend log shows `POST /shell/exec 200 OK`. |
| 5.9.6 | Settings save → backend `PUT /config` + config.json | ✅ `model` field edited, tap Save, status pill flips "Modified" → "Saved", `PUT /config 200 OK` in backend log, `backend/config.json` written with the new model + nested ports + logLevel correctly dropped. |
| 5.9.7 | Settings persistence after kill+relaunch | ✅ Force-stop + relaunch: form re-hydrates to the saved model (not the default), status pill stays "Saved" (form == lastSaved). Proves DataStore round-trip. |

**Bug found + fixed during 5.9:** the SettingsViewModel
production code was defaulting to `SettingsRepo.InMemory`
(a no-op) instead of `AndroidSettingsRepo` (DataStore +
EncryptedSharedPreferences). Phase 5.7 had introduced the
abstraction but never wired it into the ViewModel — the
Compose `viewModel<SettingsViewModel>()` no-arg overload
silently used the in-memory repo, so saves to the local
DataStore were dropped on every relaunch. Fix is a
`SettingsViewModel.Factory` companion that uses the
`APPLICATION_KEY` extra + `AndroidSettingsRepo(context)`,
referenced by `SettingsScreen` via `viewModel(factory = Factory)`.
The unit tests (which pass explicit repos) were unaffected.
Commit: `4f69f74 fix(android): wire SettingsViewModel.Factory to AndroidSettingsRepo`.

**Bug found during 6.x dev stack bring-up:** the project's
`.venv` was missing `uvicorn[standard]` (the ASGI WS lib
that handles the `/chat` upgrade). `pyproject.toml` declares
the dep correctly (`"uvicorn[standard]>=0.27"`), so a fresh
`pip install -e "./backend[dev]"` would have it — the venv
just hadn't been re-installed since the dep was added. Fixed
locally with `pip install 'uvicorn[standard]'`. `make install` installs
Android tooling only; Python dependencies come from
`.venv/bin/pip install -e "./backend[dev]" -e "./webapp[dev]"`.

## Known v0.1 limitations (carry-forward TODOs)

- **The defining embedded agent loop is blocked.** `PiRunner` uses PTY +
  `os.fork()`, while Android proot returns ENOSYS for `fork(2)`. The shell,
  embedded backend, and WebView work, but Chat cannot run the two `pi` agents.
  On Python 3.12+, the same code also warns that forking a multithreaded host
  process can deadlock.
- **Embedded worker edits cannot hot-reload yet.** Flask falls back to an
  in-process WSGI mount on Android, which has no code reloader. The current
  `app_reload` event only renders a Chat banner and does not refresh the App
  WebView.
- **Prototype security: the loopback backend is unauthenticated.** Android
  localhost is shared with other apps, and the surface includes arbitrary
  `/shell/exec`, `/config`, `/chat`, and mutable Flask routes. API keys cross
  this cleartext channel, are written to backend JSON, and can appear in
  debug-build OkHttp BODY logs. Add authenticated
  transport and server-identity protection before distribution. Embedded
  Flask content and the privileged FastAPI routes currently share the same
  `http://127.0.0.1:7777` origin while WebView JavaScript is enabled, so mutable
  web content can call control routes unless they are separated/authenticated.
  The network-security and navigation allowlists restrict cleartext targets and
  top-level navigation; they are not authentication and do not comprehensively
  block HTTPS subresources. The middle-man now has both pi's read-only tool
  allowlist and the matching runtime event filter, but prompt/tool rules are not
  a complete sandbox and the worker boundary still needs enforcement.
- **Settings are not operational backend configuration.** `PUT /config`
  persists a CWD-relative, non-atomic JSON file, but startup does not load it;
  provider/model still come from defaults or environment variables and ports
  remain fixed. Android host/port changes do not rebuild active clients or
  restart the runtime, and sync failures are not surfaced.
- **Runtime recovery is incomplete.** A process that dies immediately can
  leave health at `Unknown`; an accepted service binding has no callback
  timeout; extraction failures are not translated into retryable UI; and
  health is not continuously monitored after the first success. A wedged but
  still-alive process is re-polled rather than restarted, and any successful
  `/health` response is treated as ready even when its `flask` field is down.
- **Shell cancellation and output semantics are incomplete.** The library can
  cancel a subprocess, but the HTTP/Android protocol does not expose it.
  `subprocess.Popen` merges stderr into stdout, so `stderr` remains empty.
  Android now shows a warning row when the backend reports truncated output.
- **`ShellSession` cwd tracking is heuristic and process-global.** Only a
  leading `cd <path>` is recognized, concurrent callers share the same cwd,
  and every command still runs in a fresh shell.
- **Chat and agent sessions are process-global.** Clients share the two agent
  conversations and receive one another's events; queues may drop old events,
  offline sends can be lost, and there is no replay/history persistence.
- **Verification automation is incomplete.** There is no CI and no Python
  lint/type-check configuration. Android instrumentation compiles but was not
  run during the latest verification.
- **Distribution is unfinished.** Generated rootfs/native artifacts are
  Git-ignored and architecture-specific; a fresh checkout must run
  `make runtime`. The current x86_64 debug APK is ~370.6 MB decimal, release
  signing/minification are unfinished, the project license is TBD, and PRoot's
  GPL/source-distribution obligations must be resolved.
- **Pi uses `--no-session`.** Per-process pi session files are intentionally
  disabled, but orchestrator-level chat/task history and reconnect replay have
  not been implemented.
- **Repository state:** `main` is synchronized with `origin/main`; the old
  statement that the project had no remote was obsolete.
---

## Quick reference

**Run the dev stack:**
```bash
python3 -m venv .venv
.venv/bin/pip install -e "./backend[dev]" -e "./webapp[dev]"
# Set the API key for the default model (one-time, in your shell rc).
# See docs/pi-config.md for the full story.
export OPENCODE_API_KEY="sk-..."
./backend/scripts/dev.sh   # exports the repo webapp path and starts uvicorn + Flask
# in another shell:
curl http://127.0.0.1:7777/health    # {"status":"ok","flask":"up"}
curl -X POST http://127.0.0.1:7777/shell/exec -H 'Content-Type: application/json' \
     -d '{"command": "ls --color=auto /tmp"}'
```

**Run tests:**
```bash
.venv/bin/python -m pytest backend/ webapp/ -v  # 126 passed on 2026-08-14
./scripts/tests/runtime-tools-test.sh           # 30 passed

cd android
./gradlew --no-daemon :app:testDebugUnitTest  # 175 passed
./gradlew --no-daemon :app:lintDebug :app:assembleDebug
./gradlew --no-daemon :app:assembleDebugAndroidTest
# Run connected instrumentation separately with a matching emulator/device.
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
# when done: merge to main, remove worktree, delete branch
```
