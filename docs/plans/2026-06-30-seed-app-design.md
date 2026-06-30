# Seed — Design

> A self-improving Android app: the APK is an immutable shell with four screens (App, Chat, Shell, Settings); an embedded Linux runtime (proot + Alpine) hosts a Python orchestrator that drives two `pi` agent instances (a "middle-man" for intent and a "worker" for building); the worker mutates a Flask + SQLite web app inside the runtime; the App screen shows the result. By end of day 1, the user has the web app they asked for.

**Status:** v0.1 design, validated through brainstorming session 2026-06-30.
**Owner:** seed-dev
**License:** TBD (note: proot is GPL — see Risks)

---

## 1. Vision

A consumer-tier Android app where:

- Day 1, the user opens a chat and says *"I want a habit tracker."*
- The chat asks one or two clarifying questions, then a coding agent edits a Flask web app living on-device.
- The App screen (a WebView) hot-reloads to show the new feature.
- The user keeps chatting. The app grows.

The Android APK is a **thin shell** that never changes. All intelligence lives inside an embedded Linux runtime the app bundles. The "self-improving" loop applies to the web app inside the runtime, not the APK.

---

## 2. Target user & security stance

- **Target user (ambition):** consumer / non-technical. Anyone.
- **v0.1 reality:** the developer is the user. We optimise for the dev-iteration loop, not the consumer install flow.
- **Security stance:** **yolo mode.** The Android sandbox protects the device. Inside the embedded Linux runtime, the worker agent has full access. The middle-man agent has read-only access to `/seed/app/` (enforced by both system prompt and a backend tool-call filter). No additional security layer in v0.1.
- **Direct shell access** is a first-class screen in the app, not a hidden escape hatch. See §6.3.

---

## 3. Architecture

Three layers, cleanly separated:

```
┌──────────────────── Android (Kotlin/Compose, immutable APK) ─────────────┐
│  ┌────────┐  ┌──────┐  ┌───────┐  ┌───────────┐                           │
│  │  App   │  │ Chat │  │ Shell │  │ Settings  │   ◄── 4-section nav       │
│  └────┬───┘  └──┬───┘  └───┬───┘  └─────┬─────┘                           │
│       │ http   │ ws       │ http       │                                 │
│       │ :7778  │ :7777    │ :7777      │ (writes config.json)            │
│       ▼         ▼          ▼           ▼                                 │
│  ┌──────────────────────────────────────────────────────────────────┐     │
│  │ Foreground service: proot orchestrator                           │     │
│  │   proot ──► /bin/sh ──► python service.py                        │     │
│  │                       ├─► pi_runner (PTY wrapper)                │     │
│  │                       │     ├─► pi instance #1: MIDDLE-MAN       │     │
│  │                       │     └─► pi instance #2: WORKER           │     │
│  │                       └─► Flask (port 7778) ──► SQLite           │     │
│  └──────────────────────────────────────────────────────────────────┘     │
│                                                                            │
│  filesDir/linux/  (extracted on first run, ~150 MB)                       │
│    ├─ proot (arm64-v8a binary)                                             │
│    ├─ rootfs/  (Alpine, ~30 MB compressed)                                 │
│    └─ rootfs/home/seed/  (our backend + app)                               │
└────────────────────────────────────────────────────────────────────────────┘
```

### 3.1 Layer 1 — Android shell (immutable)

Kotlin/Compose app, single APK. Four screens:

- **App** — `WebView` rendering `http://127.0.0.1:7778/` (the user's web app)
- **Chat** — message list, input box, "thinking…" indicator, WebSocket to backend
- **Shell** — command input + output view, POST to backend (see §6.3)
- **Settings** — provider, model, API key, ports, log level, "show runtime info" button

The APK contains **zero LLM code.** It only talks HTTP/WS to localhost.

### 3.2 Layer 2 — Embedded Linux runtime

The runtime lives in the app's **internal storage** (`context.filesDir + "/linux"`), extracted once on first run:

```
filesDir/linux/
├── bin/proot                    # arm64-v8a proot binary
├── rootfs/                      # Alpine minirootfs
│   ├── bin/, lib/, usr/         # standard Alpine
│   └── home/seed/               # our working directory
│       ├── backend/             # our Python service
│       │   ├── service.py       # FastAPI orchestrator
│       │   ├── middleman.py     # (see §3.2.1)
│       │   ├── pi_runner.py     # PTY wrapper
│       │   ├── config.json      # provider/model/API key
│       │   ├── logs/            # rolling logs
│       │   └── prompts/         # system prompts
│       │       ├── middleman.md
│       │       └── worker.md
│       └── app/                 # the user's growing web app
│           ├── app.py           # Flask
│           ├── templates/
│           ├── static/
│           └── db.sqlite
```

**Lifecycle:**
- Android **foreground service** keeps the proot process alive while the app is in use.
- The service spawns `proot -r rootfs /bin/sh -c "cd /home/seed/backend && python service.py"`.
- Service exposes port 7777 inside the runtime; the host (Android) maps it to the same `127.0.0.1:7777` (no NAT needed, proot shares the network namespace by default).
- **Update path:** APK updates (Google Play internal track, or sideload). Rootfs updates ship with the APK. `git init` in `/home/seed/app/` from day 1 so user can rollback manually.

**ABI strategy (v0.1):** arm64-v8a only. Covers ~95% of modern Android phones. We expand ABIs in v0.2 if needed.

#### 3.2.1 Two pi instances

`service.py` spawns **two** `pi` subprocesses in PTYs, each preloaded with its own system prompt file:

- **Middle-man** (`prompts/middleman.md`) — extracts intent, asks 1–2 clarifying questions, emits a fenced JSON block when ready. Read-only access to `/home/seed/app/`. Same model as the worker.
- **Worker** (`prompts/worker.md`) — receives the structured task, edits files in `/home/seed/app/`, runs shell commands, reports when done. Full access (yolo).

Both use the **same model from `config.json`** (per v0.1 simplification; per-instance model override is a v0.2 feature). Reusing `pi` for both means:

- One agent runtime to install (the user already has it / we ship it)
- One provider config (the user's existing `pi` provider setup)
- One way to swap models
- Same tool surface, same streaming behavior

**Tool-call filter:** `pi_runner.py` rejects any non-readonly tool call from the middle-man (defense in depth — the system prompt says read-only, the code enforces it).

### 3.3 Layer 3 — User's web app (the thing that grows)

Flask + SQLite + static HTML/CSS/JS. Starts as a "Hello, what should I become?" skeleton and gets edited by the worker agent on each request.

**Stack:**
- **Backend:** Python 3, Flask 3, SQLite (stdlib `sqlite3`)
- **Frontend:** static HTML + vanilla JS + minimal CSS (no React/Vue in v0.1 — keep it dumb)
- **Templating:** Jinja2 (for the initial skeleton)
- **Helpers:** a `seed.fetch()` JS helper for calling Flask endpoints
- **Styling:** minimal reset, system font, light/dark aware

**Initial skeleton:**
- `app.py` — Flask with `/` (renders index) and `/api/ping` (health)
- `templates/index.html` — single "hello" card
- `static/style.css` — minimal reset + dark/light
- `static/app.js` — `seed.fetch()` helper
- `db.sqlite` — empty, with one `meta(key, value)` table

---

## 4. Components & file layout

### 4.1 Android app

```
app/
├── build.gradle.kts
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/seed/app/
│   │   ├── MainActivity.kt
│   │   ├── SeedApp.kt                       # Application class
│   │   ├── ui/
│   │   │   ├── nav/SeedNav.kt               # 4-section nav graph
│   │   │   ├── chat/ChatScreen.kt
│   │   │   ├── chat/ChatViewModel.kt
│   │   │   ├── app/AppScreen.kt             # WebView
│   │   │   ├── shell/ShellScreen.kt
│   │   │   ├── shell/ShellViewModel.kt
│   │   │   ├── settings/SettingsScreen.kt
│   │   │   └── settings/SettingsViewModel.kt
│   │   ├── data/
│   │   │   ├── BackendApi.kt                # Retrofit interface
│   │   │   ├── ChatWebSocket.kt
│   │   │   └── SettingsRepo.kt              # DataStore-backed
│   │   ├── runtime/
│   │   │   ├── RuntimeService.kt            # Foreground service
│   │   │   ├── ProotRunner.kt               # spawns proot
│   │   │   └── RuntimeExtractor.kt          # first-run extraction
│   │   └── bootstrap/
│   │       └── SetupScreen.kt               # first-run wizard
│   └── res/
│       ├── layout/, values/, drawable/
│       └── raw/                             # proot binary, rootfs tarball
```

### 4.2 Backend (inside the runtime)

```
/home/seed/backend/
├── service.py                # FastAPI orchestrator, port 7777
├── middleman.py              # (optional helper) JSON parsing
├── pi_runner.py              # PTY wrapper, ANSI strip, tool filter
├── config.json               # {provider, model, api_key, ports}
├── logs/
│   ├── service.log
│   ├── middleman.log
│   └── worker.log
└── prompts/
    ├── middleman.md
    └── worker.md
```

### 4.3 Web app skeleton

```
/home/seed/app/
├── app.py                    # Flask, port 7778
├── requirements.txt
├── templates/
│   └── index.html
├── static/
│   ├── style.css
│   └── app.js
└── db.sqlite                 # gitignored
```

---

## 5. Data flow — build loop lifecycle

When the user types *"I want a habit tracker"*:

1. **Chat → backend** (WebSocket `ws://127.0.0.1:7777/chat`):
   ```json
   { "type": "user_message", "text": "I want a habit tracker" }
   ```
2. **Backend → middle-man**: forwards the message to middle-man's pi PTY.
3. **Middle-man thinks**, calls pi tools (read-only — e.g., `ls /home/seed/app/`, `cat app/app.py` to see current state), and either:
   - Emits a clarifying question, which the backend streams to chat:
     ```json
     { "type": "ask", "question": "Should it track streaks?", "options": ["yes", "no"] }
     ```
   - Or emits the dispatch JSON:
     ```json
     ```json
     {
       "intent": "build_feature",
       "feature": "habit_tracker",
       "spec": "Build a habit tracker: tracks daily streaks, single user. Add a /habits page, a daily check-in form, and a streak counter. Store habits and check-ins in SQLite. Use the existing seed app skeleton."
     }
     ```
     ```
4. **Backend → chat**: streams the question; chat shows it with answer buttons.
5. **User answers**; loop until middle-man emits a dispatch JSON.
6. **Backend → worker**: middle-man's dispatch is composed into a final prompt and handed to worker pi's PTY.
7. **Worker thinks and acts**: file edits, shell commands, text. All output streams through the PTY.
8. **Backend → chat**: streams each event as a chat card:
   ```json
   { "type": "progress", "kind": "thought|file_edit|shell|tool", "text": "..." }
   ```
9. **Worker says done**; backend forwards a summary to chat:
   ```json
   { "type": "complete", "summary": "Created 3 files, ran migrations, app is live at /habits" }
   ```
10. **App screen auto-reloads** the WebView at `http://127.0.0.1:7778/habits` (debounced 500ms after the last file change).
11. **State persistence**: middle-man logs the task to `logs/tasks.jsonl`; pi contexts reset between requests; files persist on disk; the user can `cd /home/seed && cat logs/tasks.jsonl` to see history.

**Design choices baked in:**
- **WebSocket** for chat streaming (live progress, not 2-min waits).
- **Middle-man persists conversations** so Android can drop/reconnect without losing context (replays from last seen message ID).
- **Web app is the source of truth** — pi can always read it; if state gets weird, drop into Shell and `sqlite3 /home/seed/app/db.sqlite`.
- **No undo in v0.1** — edits apply directly. `git init` in `/home/seed/app/` from day 1 (so user can `git diff`, `git checkout` manually).

---

## 6. Per-section behaviour

### 6.1 App screen

- `WebView` at `http://127.0.0.1:7778/`
- Pull-to-refresh triggers a `webView.reload()`
- WebView auto-reloads 500ms after backend signals "complete" (debounced)
- JS enabled, DOM storage enabled, file access disabled, mixed content disabled
- No navigation outside `127.0.0.1:7778` — WebView `shouldOverrideUrlLoading` blocks everything else

### 6.2 Chat screen

- `LazyColumn` of messages (user bubbles right, agent cards left, status pills top)
- Input row at the bottom with send button
- WebSocket-backed; auto-reconnect on drop
- Streams `progress` events as collapsible cards
- `complete` events trigger an App-screen refresh
- `error` events surface as a red banner with retry

### 6.3 Shell screen

- Single-line command input + multiline output view (monospaced, scrollable)
- HTTP POST to `/shell/exec` with `{ "command": "..." }`
- Backend runs command inside a **PTY** (Python `pty` module) → fixes ~80% of "command behaves weird" cases
- Output: stdout + stderr combined, ANSI captured and rendered
- Cancellation: long-running commands get a Cancel button (sends SIGTERM)
- Truncation: output capped at 5,000 lines / 1 MB; shows "open full output in… (v0.1.1)" placeholder
- History: local cache of last 50 commands, dropdown above input
- Working directory: defaults to `/home/seed/`; persists `cd` between calls (backend tracks `cwd` in the session)

**Hard limits** (cannot fix without Option A — full terminal emulator):
- No real `vim`/`nano`/`htop` (use `cat` instead)
- No `ssh` interactive prompts
- No paginators (`git log` with no `| cat` will hang)

These are acceptable for v0.1. We document them.

### 6.4 Settings screen

- **Provider:** dropdown (Anthropic, OpenAI, Google, …) — populated from `pi`'s supported providers
- **Model:** text field (free input)
- **API key:** secure field (stored in Android EncryptedSharedPreferences; mirrored to `/home/seed/backend/config.json` on save)
- **Ports:** number fields for backend (default 7777) and Flask (default 7778)
- **Log level:** dropdown (debug/info/warn/error)
- **Runtime info:** shows proot status, rootfs size, uptime, last task
- **Danger zone:** "Stop runtime", "Wipe user data" (deletes `/home/seed/app/`, prompts confirmation)

---

## 7. System prompts

Both prompts live in `/home/seed/backend/prompts/`. Editable from the Shell screen (`vim` not available, but `cat > file.md` works).

### 7.1 `middleman.md` (sketch)

```
You are the middle-man for a self-improving app.

The user describes what they want in natural language.
Your job:
  1. Understand the intent.
  2. Ask 1–2 clarifying questions if the request is ambiguous
     (tradeoff: too many questions annoy the user; too few
     and the worker builds the wrong thing).
  3. When you have enough info, output a fenced JSON block:
     ```json
     {
       "intent": "build_feature" | "fix_bug" | "answer_question",
       "spec": "<precise, complete task for the worker>"
     }
     ```
     and end your turn.

You have read-only access to /home/seed/app/. Use it to check
the current state before crafting the spec. Do not edit files.

You can run read-only shell commands (ls, cat, grep, sqlite3
queries). Do not modify anything.

If the user is just chatting or asking a question (not a build
request), answer directly and DO NOT emit the JSON block.
```

### 7.2 `worker.md` (sketch)

```
You are the worker for a self-improving Flask app.

You receive a precise task from the middle-man. Your job:
  1. Read the relevant files in /home/seed/app/ to understand context.
  2. Plan the change. State the plan briefly.
  3. Make the edits (file_edit tool).
  4. Run shell commands to install deps, restart Flask, test.
  5. Verify it works (curl the endpoint, check the DB, etc.).
  6. Report a one-paragraph summary of what you did.

Stack: Python 3, Flask 3, SQLite (stdlib), static HTML/CSS/JS.
Frontend: keep it simple. No React, no build step. Use the
seed.fetch() helper in static/app.js.

You have full access. No approval needed. No confirmations.

When done, output: <task:done summary="..."/>
```

---

## 8. Bootstrap & install flow (v0.1)

1. **User installs the APK** (sideload for v0.1). No Termux install needed.
2. **First launch**: app shows a `SetupScreen` with a progress bar.
3. **Extraction** (`RuntimeExtractor.kt`):
   - Unpacks proot binary to `filesDir/linux/bin/proot`, chmod 755
   - Unpacks Alpine rootfs tarball to `filesDir/linux/rootfs/`
   - Unpacks our backend to `filesDir/linux/rootfs/home/seed/backend/`
   - Unpacks the web app skeleton to `filesDir/linux/rootfs/home/seed/app/`
   - Writes default `config.json` (empty API key — user fills in Settings)
   - **Total time:** 30–60s on a mid-range phone, ~150 MB extracted
4. **Start foreground service** (`RuntimeService`):
   - Spawns `proot -r filesDir/linux/rootfs /bin/sh -c "cd /home/seed/backend && python service.py"`
   - Service shows a persistent notification ("Seed runtime — tap to open app")
5. **Health check**: app polls `http://127.0.0.1:7777/health` every 2s
6. **Ready**: green checkmark, navigates to Chat screen
7. **First message**: user types a request, the loop starts

**Update path (later):**
- APK updates ship new proot binary + rootfs + backend
- On update, the extractor checks `seed_version.json` and re-extracts only what changed
- User data in `/home/seed/app/` is preserved across updates

---

## 9. Self-improvement scope (yolo, defined)

The worker can modify **anything inside the embedded runtime**:
- The web app files (`/home/seed/app/`) — its primary target
- The backend files (`/home/seed/backend/`) — e.g., updating `service.py` to fix bugs the user reported
- The system prompts (`/home/seed/backend/prompts/`) — it can edit its own instructions
- The rootfs system packages — `apk add` to install new tools
- The pi installation — `npm install -g @earendil-works/pi-coding-agent@latest`

The worker **cannot** modify the Android APK. The APK is signed and signed-only updates are from the developer (us).

**Implication:** the worker can effectively rewrite *everything* the user sees inside the runtime, including its own tools and prompts. This is the "self-improving" property — bounded by the APK shell, unbounded within the runtime.

---

## 10. Error handling

| Failure | Detection | Response |
|---|---|---|
| Proot dies | Service `onDestroy` or process gone | Service restarts proot; chat shows "runtime restarting…" banner |
| pi subprocess dies | PTY EOF | `pi_runner.py` respawns the affected agent (middle-man or worker); task continues from last checkpoint |
| LLM API error (4xx/5xx) | pi's tool output | Middle-man translates to "the model is having trouble, want to try again?"; user can retry |
| Network down | No HTTP at all | Backend returns 503; chat shows "no network" banner; Shell still works for local commands |
| Flask crashes | `/api/ping` returns 5xx | Worker is told to investigate; App screen shows "app is down" placeholder |
| Port conflict | Bind fails on startup | Settings shows "port X in use"; user picks different port; service restarts |
| Android kills background service | `onTaskRemoved` | Use `startForeground` with `FOREGROUND_SERVICE_TYPE_DATA_SYNC`; re-start on boot via `BOOT_COMPLETED` receiver |
| Out of disk | `OSError(28)` from extractor | Show "need 200 MB free, have X MB" message; abort setup |

---

## 11. Testing

**Unit tests** (Kotlin + JVM):
- `PiRunnerTest` — PTY spawn, ANSI strip, tool-call filter
- `BackendApiTest` — Retrofit contract tests with MockWebServer
- `SettingsRepoTest` — DataStore roundtrip

**Unit tests** (Python, run inside the runtime):
- `service_test.py` — FastAPI app, mocked pi
- `pi_runner_test.py` — PTY lifecycle, EOF handling, restart
- `middleman_test.py` — JSON block parsing, intent classification

**Integration tests** (on-device, manual or via adb):
- Cold-start → ready in <90s
- "Add a /todos page" → /todos returns 200, page renders, item persists across restart
- Shell: `echo hi` → "hi\n" in <1s
- Shell: `python -c "print(1+1)"` → "2\n"
- Shell: long command → cancel button works
- Worker crash mid-task → backend respawns, task resumes
- Kill the app from recents → reopen → runtime is back in <5s

**What we don't test (v0.1):**
- End-to-end AI behavior ("does the worker build a good habit tracker?") — manual only
- Model quality — out of scope

---

## 12. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | **PTY + pi streaming is fragile.** pi is a TUI; ANSI redraws can leak into chat. | M | Strip ANSI in `pi_runner.py`; pin pi version in `package.json`; expose a "raw mode" toggle in Shell for debugging. |
| 2 | **Middle-man "read-only" is enforced by system prompt, not code.** | M | Backend rejects any non-readonly tool call from middle-man (defense in depth). |
| 3 | **Android kills the foreground service** under memory pressure. | M | Use `startForeground` with the right type; re-start on `BOOT_COMPLETED`; show persistent notification. |
| 4 | **Port 7777/7778 conflicts** with other apps. | L | Pick uncommon ports; allow override in Settings; bind to `127.0.0.1` only. |
| 5 | **No undo in v0.1** — bad edit = lost work. | M | `git init` in `/home/seed/app/` on first run; expose `git status` / `git diff` shell helpers in the prompt. |
| 6 | **First-run extraction is slow** (30–60s) and uses 150 MB. | L | Show a progress bar; mention size in the setup screen; allow resuming. |
| 7 | **Flask debug mode exposes tracebacks** in v0.1. | L | Fine for local dev; switch to gunicorn in v1.0. Disable Werkzeug debugger PIN. |
| 8 | **proot is GPL.** Our APK ships GPL'd bits. | L | Ship proot source / credit in About screen; consult counsel before public distribution. |
| 9 | **Alpine rootfs goes stale** (security updates in glibc, openssl, etc.). | M | Track Alpine stable releases; ship rootfs updates with APK; document in CHANGELOG. |
| 10 | **UserLAnd was abandoned after doing almost exactly this.** It suggests the architecture is hard to maintain long-term. | H | v0.1 is a dev tool, not a product. If we ship v1.0, budget ongoing maintenance. |

---

## 13. Out of scope (v0.1)

- Authentication / multi-user
- Multiple projects (one web app per device)
- Local-model support (cloud API only via pi)
- Cloud sync / backup
- App Store distribution (sideload only for v0.1)
- Multi-ABI builds (arm64-v8a only)
- iOS / desktop
- Public website / landing page

These are tracked as future work, not abandoned — they have clear paths but aren't v0.1 priorities.

---

## 14. Open questions

None blocking. All deferred items are in §13.

---

## 15. Glossary

- **APK** — Android Package, the installable app file
- **proot** — user-space `chroot`/`bind`-mount implementation, runs Linux binaries on Android without root
- **PTY** — pseudo-terminal, gives a process a fake TTY so it behaves as if attached to a real terminal
- **Alpine** — minimal Linux distribution (~5 MB base), uses musl libc and busybox
- **pi** — the `pi-coding-agent` CLI tool (open source, Node.js), used here as our agent runtime
- **middle-man** — the pi instance that talks to the user and crafts tasks
- **worker** — the pi instance that edits files and runs shell commands
- **yolo mode** — agent runs commands and edits files without confirmation prompts
