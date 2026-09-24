# Middleman–Worker Coordination Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Give Seed one conversational voice, with backend-tracked Worker tasks, progress, cancellation, final summaries, and independent durable Pi role sessions.

**Architecture:** Keep two Pi RPC processes with the backend orchestrator as task-state authority. The Worker emits events/reports to the orchestrator; the orchestrator delivers selected messages to Middleman and a machine-readable task status to Android. Explicitly resume role-specific Pi sessions and mark unfinished work interrupted on restart.

**Tech Stack:** Python asyncio/FastAPI/Pi RPC, Kotlin/Compose/OkHttp, pytest/Gradle, Android PRoot.

---

## Task 1: Backend task state and status protocol

**Files:** `backend/seed_backend/orchestrator.py`, `backend/seed_backend/chat.py`, `backend/seed_backend/events.py`; tests `backend/tests/test_chat_ws.py`, `backend/tests/test_middleman_dispatch.py` and new `backend/tests/test_task_lifecycle.py`.

1. Write failing fake-runner tests: validated Middleman dispatch creates ID and `pending`/`running` events, disconnect/reconnect receives snapshot, concurrent dispatch does not silently overwrite active task, stale events ignored. Run focused pytest; verify red.
2. Implement bounded task record and broadcast snapshots, route malformed dispatch and failed Worker prompt to failed status. Keep raw events only as debug events. Verify green; commit checkpoint only after batch review.

## Task 2: Worker reports, Middleman summary, and user steering

**Files:** `backend/seed_backend/orchestrator.py`, `backend/prompts/{middleman,worker}.md`, backend fake Pi fixtures/tests.

1. Failing tests: meaningful Worker milestone forwarded to Middleman once, tool spam suppressed; agent *settled* event (not model `<task:done/>` alone) finalizes task, report forwarded as follow-up to Middleman, user sees Middleman's final summary; failure/exit sends fallback. A user correction during running work reaches Middleman, whose explicit steering instruction is sent to Worker with task ID.
2. Implement turn-scoped buffering, explicit milestone/result contract, RPC queue behavior (`follow_up`/`steer` while busy), delivery error handling. Verify red→green. Do not assume `prompt` success means Worker completed.

## Task 3: Immediate stop and durable role sessions

**Files:** `backend/seed_backend/orchestrator.py`, `backend/seed_backend/service.py`, `backend/seed_backend/pi_runner.py`, create `backend/seed_backend/task_store.py`; backend tests.

1. Failing tests: direct stop bypasses Middleman and sends Pi `abort`, marks cancel pending then cancelled on settlement (bounded fallback), preserves existing file edits; persisted active tasks become interrupted on restart and never auto-resume. Distinct Middleman/Worker session paths, no control session persistence; role path survives restart and runtime upgrade, no accidental shell session resume.
2. Implement private, atomic task metadata under persistent Pi directory; explicit session identities and Pi CLI `--session` resume after first creation (use RPC `get_state` to capture first session file); safe handling missing/corrupt session files. Verify full backend suite.

## Task 4: Android chat protocol and presentation

**Files:** `android/app/src/main/java/com/seed/app/data/{ChatEvent.kt,ChatWebSocket.kt}`, `android/app/src/main/java/com/seed/app/ui/chat/{ChatViewModel.kt,ChatScreen.kt,MessageBubble.kt}`; corresponding tests.

1. Failing tests: status indicator follows authoritative task events, stop sends `stop_task` without waiting for Middleman, reconnect recovers snapshot, raw JSON/Worker tool events hidden from normal chat but available in optional debug surface, errors and final Middleman summary visible.
2. Implement minimal UI/state support; preserve existing chat behavior outside tasks; verify Android unit tests/build.

## Task 5: Integration and review

1. Run `cd backend && PYTHONPATH=../webapp .venv/bin/python -m pytest -q`, `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug --offline`, `git diff --check`.
2. Rebuild matching arm64 rootfs with `RUNTIME_ARCH=arm64 ./scripts/build-runtime.sh` for on-device tests; verify role session files, task progress, mid-task correction, stop, completion/failure summary, reconnect, and restart interrupted status. Do not use actual credentials in logs/tests.
3. Review cross-layer behavior and security, document limits, commit only after tests and device evidence. No tmux/MCP, configurable agent counts, auto-resume, or Git rollback in this feature.
