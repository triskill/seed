# Runtime Readiness and Hardening Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Prevent Android from accepting a runtime without usable agents or Flask, repair the shell contract, and establish reproducible verification and security hygiene.

**Architecture:** Treat agent startup as mandatory: if either Pi runner fails, tear down all started components and fail FastAPI lifespan, allowing Android's runtime supervisor to retry. Preserve `/health` response shape (`status`, `flask`) but make HTTP success mean actual readiness, including both agents and a live Flask ping. Keep shell requests serialized and use a shell-native cwd reporting protocol rather than regex parsing; retain the endpoint only while its API contract is tested. Do not claim historical credential revocation from repository changes.

**Tech Stack:** Python 3.11+, FastAPI, pytest, Flask/httpx, Android Kotlin/Gradle, GitHub Actions.

**Validated findings (2026-09-29):** `backend/seed_backend/service.py` stores orchestrator before startup, swallows failures, `/health` checks only process liveness, and `/chat` checks only non-null. `backend/seed_backend/orchestrator.py` starts middleman then worker without rollback. `backend/seed_backend/shell.py` parses leading `cd` with a regex and shares mutable cwd through one app-wide session. `backend/seed_backend/flask_manager.py` probes ping only during startup. TODO.md documents the historic token exposure and verification gaps; the credential owner reported revocation on 2026-09-29. Contrary to the report, tracked `.pi/agent/.gitignore` already ignores `auth.json`; confirm via `git check-ignore -v .pi/agent/auth.json` and do not treat this as a missing protection. `backend/pyproject.toml` duplicates uvicorn and uses a TBD license; Android release disables minification. Existing `backend/tests/test_service_lifecycle.py::test_lifespan_survives_missing_pi_command` deliberately asserts the old behavior and must be rewritten.

**Decisions:** Agent failure is fatal to startup; `/health` remains readiness for the existing Android `HealthMonitor` (which requires an HTTP response with `flask == "up"`). Do not introduce a separate liveness URL unless an actual consumer needs it. Do not rewrite Git history or assign a license without owner approval. No worktree: implement on branch `fix/runtime-readiness-and-verification`.

---

### Task 1: Credential incident response (owner action; do first)

**Status:** Credential owner reports the historically committed token revoked and no longer valid (2026-09-29). This is owner-reported, not independently tested here.

1. Never print the token or add it to tests/logs; confirm any dependent integrations use no revoked credential.
2. Decide with repository owners whether history rewrite is worth disrupting clones/forks; revocation is the primary protection. Automated secret scanning remains in Task 6.

### Task 2: Transactional Pi startup and teardown

**Files:** Modify `backend/seed_backend/orchestrator.py:379-455`; test `backend/tests/test_orchestrator_imports.py` or add `backend/tests/test_orchestrator_startup.py` using stub runners.

1. Write an async pytest test whose middleman starts and worker raises; assert both runners are stopped, read tasks absent, and failure propagates. Also test failure of middleman start and successful retry after failure.
2. Run `.venv/bin/python -m pytest backend/tests/test_orchestrator_startup.py -q`; expect new rollback assertions to fail.
3. In `Orchestrator.start`, on startup error stop both runners (and any read tasks); preserve the original startup error if cleanup raises, logging cleanup failure. Publish unavailable role state and re-raise. Set an explicit `ready` state only after both start; clear it on stop and on detected runner failure. Review `pi_runner.py` process-health APIs before defining ready: it must not mean merely `start()` was called.
4. Run the focused tests, then the orchestrator and auto-restart suites. Commit `fix: roll back partial agent startup`.

### Task 3: Lifespan, HTTP readiness and chat gate

**Files:** Modify `backend/seed_backend/service.py:250-355,490-515`; tests `backend/tests/test_service_lifecycle.py`, `backend/tests/test_service.py` and chat endpoint tests (locate with `rg -n 'chat_endpoint|/chat' backend/tests`).

1. Rewrite `test_lifespan_survives_missing_pi_command` to assert TestClient lifespan fails, both agents and Flask/control resources are stopped, and no usable `/health` response is served. Add a worker-only failure test and a socket test for an unavailable orchestrator. Add settings-apply rollback tests where replacement and restoration both fail.
2. Run focused tests; expect old expectations/new cleanup assertions to fail.
3. Put all startup and `yield` within `try/finally` so Flask and control subprocess are stopped even on startup failure; set `app.state.orchestrator` only on successful startup, and clear it during teardown. `/chat` must check actual readiness, including after an agent crash or failed apply; close with 1011 before entering `handle_chat`. Guard settings replacement with `agent_lock`, and ensure failed rollback never leaves stale readiness.
4. Make `/health` return 503 when agents are not ready; preserve success JSON for Android. Check `HealthMonitor.kt` behavior with a non-2xx response. Test transition to unhealthy after runner exit; avoid synchronous blocking subprocess/HTTP calls on the event loop.
5. Run `.venv/bin/python -m pytest backend/tests/test_service_lifecycle.py backend/tests/test_service.py -q` and relevant chat tests. Commit `fix: gate runtime readiness on both agents`.

### Task 4: Flask application readiness after startup

**Files:** Modify `backend/seed_backend/flask_manager.py:109-147`, `backend/seed_backend/service.py:347-352`; test `backend/tests/test_flask_manager.py`, `backend/tests/test_service.py`.

1. Add tests with a live parent but failed `/api/ping` (timeout, non-200, connection error) and a healthy ping; `/health` must return 503 for failed ping and 200 only for healthy ping plus agents.
2. Run tests red. Add bounded async `is_ready()` using `httpx.AsyncClient` and `is_up()`; change `/health` to async and probe ping directly or via a short-TTL cached monitor (only if measured probe cost demands it). Avoid logging credentials or returning ping contents.
3. Run focused tests and Android `HealthMonitor` JVM tests; commit `fix: probe Flask serving readiness`.

### Task 5: Shell correctness and concurrency

**Files:** Modify `backend/seed_backend/shell.py:455-565`, `backend/seed_backend/service.py:379-405`; test `backend/tests/test_shell.py`.

1. Add failing tests for `cd /tmp && pwd`, `cd ~`, `cd -`, quoted/space-containing directories, failing `cd` preserving cwd, two concurrent calls, timeout/cancel, and stdout/stderr/exit-code preservation. Clarify whether cwd after a compound expression should persist as the shell's final cwd.
2. Run `.venv/bin/python -m pytest backend/tests/test_shell.py -q` to confirm failures.
3. Replace `_CD_LEAD_RE` with execution of the entire original expression in `sh -c`; capture final cwd via a private side channel (a per-call temporary file or dedicated descriptor emitted by a shell wrapper after the command), not stdout markers that can collide with user output. Preserve original exit status, signal/timeout behavior and cleanup; inspect `_exec_command_impl` before implementing protocol. Add a session `asyncio.Lock` around cwd read, execution and cwd update, so the shared session has deterministic sequential behavior. Document that sessions are shared across callers pending an authenticated client-session design.
4. Run shell and service tests; commit `fix: honor shell syntax and serialize cwd`.

### Task 6: Verification gate and security hygiene

**Files:** Modify `Makefile`, `backend/pyproject.toml`, `backend/tests/test_service.py`; create `.github/workflows/verify.yml`; optionally modify root `.gitignore` and `.pi/agent/.gitignore`.

1. Run `.venv/bin/python -m pytest backend/ webapp/ -q` twice; investigate any flaky process-group/readiness tests before adding them as a CI gate. Remove machine-specific path, global-default monkeypatch and fixed-port assumptions in `test_service.py`, with regressions for dynamic ports.
2. Add `make verify` for Python tests and lint/type tooling with pinned or bounded dependencies; introduce lint/type checks incrementally after baseline results. Set up CI on push/PR with Python tests, static checks and Android JVM tests/lint (inspect Gradle requirements and generated asset dependencies first). Keep device/PRoot connected tests as an explicitly separate acceptance lane, not a falsely green CI requirement.
3. Check `git check-ignore -v .pi/agent/auth.json`; existing nested ignore already protects it. Add a root-level explicit rule only as defense-in-depth, plus automated secret scanning (use a maintained scanner with version-pinned action/config and a reviewed allowlist); ensure no secret bytes appear in CI output.
4. Run `make verify`, the applicable Gradle JVM/lint commands, and validate the workflow YAML; commit `ci: add reproducible verification and secret scanning`.

### Task 7: Dependency/release policy cleanup

**Files:** Modify `backend/pyproject.toml:13-25`, `TODO.md`; review `README.md`, `android/app/build.gradle.kts:44` and release documentation.

1. Remove redundant `uvicorn>=0.27`, retaining `uvicorn[standard]>=0.27`; regenerate any generated requirements only through the documented builder, not by committing ignored artifacts. Run Python tests.
2. Inventory PRoot, Termux, Python and Android dependency licenses and consult project owner before replacing `TBD` with an actual project license; record licensing obligations in TODO/docs. Decide whether release minification is desired and add Android release-build tests before toggling it. Do not silently turn on minification as a housekeeping change.
3. Commit the dependency change; keep legal and release-policy choices as blocked, owner-assigned follow-ups until resolved.

### Final verification

Run `git diff --check`, full backend/webapp tests, Android JVM tests and lint, and a real startup smoke test with a missing Pi executable (must fail startup) plus a working runtime (must pass readiness); test Flask child outage and agent crash against `/health`. Record actual command outputs and any device-test gaps in `TODO.md`. Use verification-before-completion and requesting-code-review skills before claiming completion. Attribute the credential revocation to the owner's 2026-09-29 confirmation; do not claim independent verification.
