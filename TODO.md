# Seed — TODO

A self-improving Android app: the APK is an immutable shell with four screens
(App, Chat, Shell, Settings); an embedded Linux runtime (proot + Alpine) hosts
a Python orchestrator that drives two `pi` agent instances (a "middle-man" for
intent and a "worker" for building); the worker mutates a Flask + SQLite web
app inside the runtime; the App screen shows the result.

**Full design:** [`docs/plans/2026-06-30-seed-app-design.md`](docs/plans/2026-06-30-seed-app-design.md) (494 lines, vision + architecture + risks).
**Detailed phase tasks:** [`docs/plans/2026-06-30-seed-v0.1-bootstrap.md`](docs/plans/2026-06-30-seed-v0.1-bootstrap.md) (Phase 0 + Phase 1 task-by-task spec).
**Pi config:** [`docs/pi-config.md`](docs/pi-config.md) — project-local `.pi/agent/`, default model (`deepseek-v4-flash` on `opencode-go`), `OPENCODE_API_KEY` env var, `SEED_PI_*` overrides.

---

## Status at a glance

| Phase | What | Status |
|---|---|---|
| 0 | Project skeleton + local backend + web app | ✅ done (8/8) |
| 1 | Shell endpoint (PTY-backed) | ✅ done (5/5) |
| 2 | pi runner (PTY wrapper, ANSI strip, tool filter) | ✅ done (6/6) |
| 3 | Middle-man + worker orchestration | ✅ done (7/7) |
| 4 | System prompts + first real agent loop | ✅ done (4/4) |
| 5 | Android shell (4 screens, nav, WebView) | 🟡 in progress (5/9) |
| 6 | Android ↔ backend wiring | ⬜ not started |
| 7 | Runtime extraction (proot + Alpine) | ⬜ not started |
| 8 | Foreground service | ⬜ not started |
| 9 | First-run setup wizard | ⬜ not started |
| 10 | End-to-end polish | ⬜ not started |

Tests: **102/102 backend + 29/29 Android unit** passing.

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

## 🟡 Phase 4 — System prompts + first real agent loop (3/4)

| # | Task | Files | Notes |
|---|---|---|---|
| 4.1 | `middleman.md` system prompt | `backend/prompts/middleman.md` (new), `backend/seed_backend/orchestrator.py` (wired via `--append-system-prompt`) | Defines the middle-man's role: read-only access to `/home/seed/app/`, ask 1–2 clarifying questions if ambiguous, emit a fenced JSON dispatch block when ready, answer questions directly (no JSON). Describes the wire format the orchestrator expects (`{"intent","feature","spec"}`). Updated `pi_cmd_for_role` to pass `--append-system-prompt <file>` so the role-specific prompt is injected at spawn time. |
| 4.2 | `worker.md` system prompt | `backend/prompts/worker.md` (new), `backend/seed_backend/orchestrator.py` | Worker prompt: read state, plan, edit, verify (`curl` the new route, check the DB schema), emit `<task:done summary="..."/>` when done. Includes the `<task:done summary="..."/>` marker format the orchestrator's worker read loop watches for (Task 3.6 + Phase 4 enrichment). |
| 4.3 | Orchestrator speaks pi's RPC protocol | `backend/seed_backend/events.py`, `backend/seed_backend/orchestrator.py`, `backend/tests/fixtures/fake_pi*.py`, `backend/tests/test_events.py`, `backend/tests/test_prompts.py` | The orchestrator was built assuming pi outputs plain text. Real `pi --mode rpc` expects JSON commands on stdin (`{"type":"prompt","message":"..."}`) and emits JSONL events on stdout (`message_update`, `tool_execution_start`, `turn_end`, etc.). Added `translate_pi_line` (in `events.py`) that unwraps pi's events back to plain text deltas / tool-call JSON / turn-boundary signals. `send_to_middleman` and the worker send now wrap messages in `{"type":"prompt",...}`. The fake pi fixtures were updated to parse the JSON wrapper and use the `message` field as the prompt, so the existing 59 tests still pass without changes. New `scripts/demo_phase4_smoke.py` exercises the full stack with real `pi` (sends a question, gets a streamed text response). 13 new unit tests for the translator + 6 sanity tests for the prompt files. |
| 4.4 | Real end-to-end build with live iteration | `backend/prompts/middleman.md`, `backend/prompts/worker.md`, `backend/seed_backend/flask_manager.py`, `backend/seed_backend/events.py` | Drove a real build (`Add a tiny /hello route`) with the local `opencode-go` / `deepseek-v4-flash` config. Observed the full chain: middle-man inspects state, emits a dispatch, worker edits `app.py` via the `edit` tool, verifies with `curl`, emits `<task:done summary="..."/>`, orchestrator broadcasts `complete` + `app_reload`. Three concrete issues found and fixed: (a) prompts hardcoded `/home/seed/app/` — replaced with `$SEED_APP_PATH` and threaded it through `pi_env_for_role` (env var, not argv); (b) the translator didn't handle `message_end` events, so the cheap deepseek model (which doesn't stream `text_delta` chunks) emitted the done marker only in `message_end` and the orchestrator's scan never saw it — added a `message_end` case that extracts the final text from `message.content` text blocks; (c) Flask wasn't in debug mode, so worker edits to `app.py` weren't picked up by the reloader and the worker had to manually `kill` + restart Flask (forbidden in production) — added `FLASK_DEBUG=1` to the subprocess env in `FlaskManager.start()`. After fixes, the worker verified the new route on the first `curl` attempt. The chat UI got `complete` + `app_reload` and the script exited cleanly. |

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

## 🟡 Phase 5 — Android shell (4 screens, nav, WebView) (5/9)

| # | Task | Files | Notes |
|---|---|---|---|
| 5.1 | Gradle init + blank MainActivity | `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradlew` + `gradle/wrapper/`, `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/seed/app/MainActivity.kt`, `android/app/src/main/java/com/seed/app/SeedApp.kt`, `android/app/src/main/res/values/{strings,colors,themes}.xml`, `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml`, `android/app/src/main/res/drawable/ic_launcher_foreground.xml`, `android/README.md` | Modern AGP 8.5.0 + Kotlin 1.9.24 + Compose BOM 2024.06.00 (Material 3); minSdk 26, targetSdk 34. Pinned versions in the top-level `build.gradle.kts` with `apply false` so a fresh clone is deterministic. No `local.properties` in the repo — `ANDROID_HOME` env var (or auto-generated by Android Studio) is the source of truth, per the project `.gitignore`. `buildConfigField` exposes `WEBAPP_DEV_URL=http://10.0.2.2:7778/` and `BACKEND_DEV_URL=http://10.0.2.2:7777/` for the WebView (5.3) and WebSocket client (Phase 6) to read at compile time. Verified: `./gradlew assembleDebug` produces a 15 MB `app-debug.apk`; AAPT confirms `package: com.seed.app`, `minSdkVersion: 26`, `targetSdkVersion: 34`, label "Seed". **Buildable without a device** — but 5.2+ need an emulator to verify the UI. |
| 5.2 | 4-section navigation skeleton | `android/app/src/main/java/com/seed/app/ui/nav/SeedNav.kt` (new), `android/app/src/main/java/com/seed/app/ui/{app,chat,shell,settings}/*Screen.kt` (new), `android/app/src/main/java/com/seed/app/MainActivity.kt` (modified), `android/app/src/main/res/values/strings.xml` (modified) | `SeedNav` is a `Scaffold` that owns a Material 3 `NavigationBar` (4 items: App / Chat / Shell / Settings) and a `NavHost` with matching routes. Standard bottom-nav tap idiom: `popUpTo` start + `saveState`/`restoreState` + `launchSingleTop`. The 4 `*Screen.kt` files are placeholders (centered title + subtitle string) — their real composables land in 5.3 (App/WebView), 5.4 (Chat), 5.5 (Shell), 5.6 (Settings). `MainActivity` now calls `SeedNav()`; the `SeedPlaceholderScreen` from 5.1 is gone. `R.string.tab_*` + `R.string.*_screen_title/subtitle` added. Auto-mirrored Chat icon (no deprecation warning; mirrors correctly in RTL). Verified: `assembleDebug` produces 16 MB `app-debug.apk`; lint clean on the new files; 102/102 backend tests still pass. **Visual verification (tap each tab → screen changes) still needs an emulator.** |
| 5.3 | WebView in App screen | `android/app/src/main/java/com/seed/app/ui/app/AppScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/app/WebViewConfig.kt` (new), `android/app/src/main/res/xml/network_security_config.xml` (new), `android/app/src/test/java/com/seed/app/ui/app/WebViewConfigTest.kt` (new), `android/app/src/main/AndroidManifest.xml` (modified), `android/app/build.gradle.kts` (modified) | Replaces the 5.2 placeholder with `AndroidView` wrapping a `SwipeRefreshLayout` → `WebView` that loads `BuildConfig.WEBAPP_DEV_URL` (http://10.0.2.2:7778/ on emulator, http://127.0.0.1:7778/ on a physical device that uses `adb reverse`). **Security model is two narrow allowlists, defence in depth:** (1) `res/xml/network_security_config.xml` permits cleartext HTTP only to 10.0.2.2 + 127.0.0.1 (everything else stays HTTPS-only at the platform level); (2) `WebViewConfig.isAllowedUrl` (in a new module, pure function over `java.net.URI`) is the second filter — `WebViewClient.shouldOverrideUrlLoading` returns `true` (= block) for any URL whose host isn't in `{10.0.2.2, 127.0.0.1}` or whose scheme isn't http(s). Uses the modern 3-arg `shouldOverrideUrlLoading(view, request)` overload (avoids the deprecation warning on the 2-arg one). Pull-to-refresh: `SwipeRefreshLayout` (from `androidx.swiperefreshlayout:1.1.0`) wraps the WebView and calls `webView.reload()` on swipe. Picked over Compose's `PullToRefreshBox` because that's only in material3 1.3+ and our Compose BOM (2024.06.00) ships material3 1.2.1. Lifecycle: `WebView` is `remember`-ed so it survives recomposition; `DisposableEffect(Unit)` calls `webView.destroy()` on dispose so the renderer thread doesn't leak. **Unit tested (JVM):** `WebViewConfigTest` — 13 tests covering scheme / host / userinfo / host-suffix / case / malformed-URL edge cases. Verified: `./gradlew assembleDebug` → BUILD SUCCESSFUL (16 MB APK); `./gradlew testDebugUnitTest` → 13/13 passing; `./gradlew lintDebug` → clean; `aapt dump` confirms `INTERNET` permission + `networkSecurityConfig` resource ref in the packaged APK; 102/102 backend tests still pass. **Visual verification (tap App tab → WebView loads the host webapp, pull-to-refresh works) still needs an emulator** — tracked in 5.9. |
| 5.4 | Chat screen UI | `android/app/src/main/java/com/seed/app/ui/chat/ChatScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/chat/ChatMessage.kt` (new), `android/app/src/main/java/com/seed/app/ui/chat/ChatViewModel.kt` (new), `android/app/src/main/java/com/seed/app/ui/chat/MessageBubble.kt` (new), `android/app/src/test/java/com/seed/app/ui/chat/ChatViewModelTest.kt` (new) | Replaces the 5.2 chat placeholder with a real chat surface: a `LazyColumn` of `MessageBubble` rows driven by a `ChatViewModel` (`StateFlow<List<ChatMessage>>`), plus a bottom input bar (text field + send button). **Data model** — new `ChatMessage.kt` module: sealed class `ChatMessage` with three subclasses (`User` / `Agent` / `System`) so the Compose `when` in `MessageBubble` is exhaustively checked at compile time; `AgentRole` enum (MIDDLEMAN / WORKER) with `displayName` for card labels; `SystemEventKind` enum (COMPLETE / APP_RELOAD / ERROR) for orchestrator events. The companion-object `newId()` / `now()` helpers exist because Kotlin doesn't resolve private members of the enclosing class in a nested data class's default-parameter expression. **ChatViewModel** — `StateFlow<List<ChatMessage>>` (starts empty), `StateFlow<String> inputText`, `onInputChange(String)` passthrough, `send()` that trims the input, no-ops on blank, appends a User message, clears the input. **No backend wiring** — that's Phase 6.3. The public API (`messages` / `inputText` / `onInputChange` / `send`) is stable; Phase 6.3 only adds work inside `send()` and a flow-collector for the WebSocket events. **MessageBubble** — three private variants: `UserMessageBubble` (right-aligned, primary container fill, asymmetric corner radius so it "points" at the user), `AgentMessageCard` (full-width card, surfaceVariant fill, role label above body), `SystemMessageBanner` (full-width, colour keyed to `SystemEventKind`: tertiary / secondary / error container). **ChatScreen** — `LazyColumn` keyed on `message.id` (preserves row identity across list edits), `LaunchedEffect(messages.size)` auto-scrolls to bottom on new messages, `imePadding()` on the outer Column so the input bar is pushed above the soft keyboard, `KeyboardOptions(imeAction = ImeAction.Send)` + `KeyboardActions(onSend = send)` so the IME Send key submits, `testTag` semantics on list / input / send for future UI tests, `viewModel()` defaults to the NavBackStackEntry so the message list survives tab switches. **Unit tested (JVM):** `ChatViewModelTest` — 8 tests covering initial state, `onInputChange`, `send` (appends / clears / trims / no-op on empty / no-op on whitespace), and multi-message order. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (16 MB APK, no new deps); `./gradlew :app:testDebugUnitTest` → 21/21 passing (13 WebView + 8 ChatViewModel); `./gradlew :app:lintDebug` → clean; 102/102 backend tests still pass. **Visual verification** (type a message, tap Send, see the bubble appear; pull the soft keyboard up, see the input bar stay above it) still needs an emulator — tracked in 5.9. |
| 5.5 | Shell screen UI | `android/app/src/main/java/com/seed/app/ui/shell/ShellScreen.kt` (modified), `android/app/src/main/java/com/seed/app/ui/shell/OutputLine.kt` (new), `android/app/src/main/java/com/seed/app/ui/shell/ShellViewModel.kt` (new), `android/app/src/main/java/com/seed/app/ui/shell/OutputLineRow.kt` (new), `android/app/src/test/java/com/seed/app/ui/shell/ShellViewModelTest.kt` (new) | Replaces the 5.2 shell placeholder with a real shell surface: a top input row (text field + Run button + Cancel button) and a bottom `LazyColumn` of `OutputLineRow` rows driven by a `ShellViewModel` (`StateFlow<List<OutputLine>>`). **Data model** — new `OutputLine.kt` module: sealed class `OutputLine` with four subclasses (`Command` / `Stdout` / `Stderr` / `Exit`) so the Compose `when` in `OutputLineRow` is exhaustively checked at compile time. **ShellViewModel** — `StateFlow<List<OutputLine>>` (starts empty), `StateFlow<String> input`, `onInputChange(String)` passthrough, `submit()` that trims the input, no-ops on blank, appends a `Command` + a fake `Exit(0)`, clears the input. **No backend wiring** — that's Phase 6.4. The public API (`output` / `input` / `onInputChange` / `submit`) is stable; Phase 6.4 only adds work inside `submit()` and adds an `isExecuting` flow that drives the Cancel button. **OutputLineRow** — four private variants: `CommandLine` (monospaced two-tone `AnnotatedString`: `$` prompt in primary, command in onSurface), `PlainLine` (monospaced, onSurface — for Stdout), `StderrLine` (monospaced, error colour), `ExitLine` (monospaced, muted `onSurfaceVariant`, renders `[exit N]`). All rows are 13 sp monospaced so columns line up the way they would in a real terminal. **ShellScreen** — input at TOP, output at BOTTOM (the plan calls for this layout, vs. the chat screen's input-at-bottom: the shell is a "form + log" pattern, not conversational, so the input is always visible and easy to reach with one hand). `LazyColumn` keyed on `OutputLine.id`, `LaunchedEffect(output.size)` auto-scrolls to the bottom on new lines, `imePadding()` on the outer Column, `KeyboardOptions(imeAction = ImeAction.Send)` + `KeyboardActions(onSend = submit)` so the IME Send key submits. **Cancel button** is rendered (so the layout is stable when Phase 6.4 enables it) but is permanently `enabled = false` in 5.5 — there is no "command running" state. Phase 6.4 will add the `isExecuting` flow and `cancel()` method. `testTag` semantics on list / input / run / cancel for future UI tests. **Unit tested (JVM):** `ShellViewModelTest` — 8 tests covering initial state, `onInputChange`, `submit` (appends Command+Exit / clears input / trims / no-op on empty / no-op on whitespace-only), and multi-submit order. Verified: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (16 MB APK, no new deps); `./gradlew :app:testDebugUnitTest` → 29/29 passing (13 WebView + 8 ChatViewModel + 8 ShellViewModel); `./gradlew :app:lintDebug` → clean; 102/102 backend tests still pass. **Visual verification** (type a command, tap Run, see the command and a fake `[exit 0]` line appear in the output; monospaced font; Cancel button visible but disabled) still needs an emulator — tracked in 5.9. |

**Android SDK setup** (one-time, not in repo):
- Command-line tools installed at `~/android-sdk/cmdline-tools/latest/`
- `sdkmanager` installed: `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
- KVM is available (`/dev/kvm`); emulator can be set up later for visual verification
- Gradle 8.7 bootstrap'd the wrapper; `gradle-wrapper.jar` + `gradlew` are committed

**Next** (buildable without device):
- 5.6: settings screen body (placeholder-level now; real widgets in their own task)
- 5.7: DataStore settings repo (unit-testable on JVM)
- 5.8: theme + system bar styling

**Needs emulator or device for visual verification:** 5.9 demo (tap each tab, verify WebView loads host webapp via `adb reverse`).

**Verification what's possible without a device:**
- ✅ `assembleDebug` produces APK
- ✅ AAPT confirms manifest, resources, version codes
- ✅ Lint runs against the project
- ❌ No visual verification (rendering, animations, taps)
- ❌ No runtime verification (do the screens work as expected?)

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
- **Pi uses `--no-session` so it doesn't persist per-process session files** — the orchestrator drives long-running pi processes for the lifetime of the FastAPI app, and persisting those sessions would pollute `~/.pi/agent/sessions/`. Chat history persistence (replaying from the last seen message ID across reconnects) is a separate concern, handled at the orchestrator level (e.g. logging to `logs/tasks.jsonl`) rather than by pi's session. Phase 4+ will add that.

---

## Quick reference

**Run the dev stack:**
```bash
python3 -m venv .venv
.venv/bin/pip install -e "./backend[dev]" -e "./webapp[dev]"
# Set the API key for the default model (one-time, in your shell rc).
# See docs/pi-config.md for the full story.
export OPENCODE_API_KEY="sk-..."
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
