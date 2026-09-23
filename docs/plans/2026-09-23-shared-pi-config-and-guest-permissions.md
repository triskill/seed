# Shared Pi Configuration and Guest Permissions Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Make Seed's terminal and both chat agents use the same Pi configuration as the single source of truth, then add best-effort guest user/group organization in a separate commit.

**Architecture:** The backend owns edits to Pi's `auth.json` and `settings.json` in one Seed runtime Pi directory; Android is a client and does not persist Pi credentials or model selection separately. Seed's terminal and every Pi subprocess set `PI_CODING_AGENT_DIR` to that directory. Commit 2 adds guest identities and app-directory permissions for organization only; PRoot guest ownership is not an Android security boundary.

**Tech Stack:** Python/FastAPI/Pi RPC, Android Kotlin/Compose/Retrofit/DataStore, Alpine/PRoot, pytest/JUnit/Android runtime tests.

---

## Before either commit

- Work in a clean isolated worktree: the current working tree already has changes in `service.py`, `BackendApi.kt`, `SettingsViewModel.kt`, tests, and `docs/pi-config.md`. Review and incorporate those changes deliberately; never overwrite or stage them accidentally. Record baseline test results.
- Reproduce the actual chat connection error with a safe test key or redacted logs. Distinguish Pi provider failure, Pi RPC failure, backend chat transport failure, and Android WebSocket failure. Never log credentials. A config refactor must not be reported as fixing the error until an on-device prompt succeeds.
- Verify Pi version and its `auth.json` schema in the bundled runtime, not only the developer-host Pi installation. Built-in provider IDs must match Pi; for example Gemini's auth ID is `google`, not necessarily its environment-variable name.

## Commit 1: Pi configuration is the only source of truth

### Task 1: Specify shared directory and tests

**Files:** `backend/seed_backend/orchestrator.py`, `backend/tests/test_process_env.py`, `backend/tests/test_agent_apply.py`, `android/app/src/main/java/com/seed/app/runtime/SeedTerminalManager.kt`, corresponding `android/app/src/test/java/com/seed/app/runtime/` tests, `docs/pi-config.md`.

1. Write failing tests asserting shell, control Pi, Middleman, and Worker resolve the same persistent directory (proposed `/home/seed/.pi/agent` inside the runtime). Assert unrelated host credentials and backend bearer capability do not enter Pi process environments. Run the focused tests; expect failures.
2. Implement a single configurable runtime Pi directory constant, set `PI_CODING_AGENT_DIR` in both terminal and Pi child environments, and remove per-role config directories. Ensure directory creation on first launch and after runtime extraction/upgrades; avoid pointing packaged/development runs at a tracked repo directory. Run focused tests to green.
3. Document that shell `pi /login` and UI use identical auth state; direct shell edits may require restarting the agents to change their selected model. Credentials in `auth.json` are readable to both Pi agents by design.

### Task 2: Implement backend configuration API

**Files:** create `backend/seed_backend/pi_config.py`, modify `backend/seed_backend/service.py`, `backend/seed_backend/provider_allowlist.py` if needed; create `backend/tests/test_pi_config.py`, modify `backend/tests/test_pi_control.py` and `backend/tests/test_agent_apply.py`.

1. Add failing tests for: enumerating built-in providers and provider-filtered models; listing configured provider IDs plus selected provider/model/thinking level without returning keys; adding/updating a provider API key without deleting other providers; selecting a provider/model only when configured and valid; reading changes made by shell directly; missing/malformed files, write failures, and unsupported provider. Assert GET and errors never contain secrets.
2. Use Pi's installed model catalog/control process for model enumeration/validation; avoid maintaining an independent hard-coded model list. Restrict UI key entry to built-in, API-key-capable providers for this phase. Treat unsupported OAuth-only providers as unsupported rather than writing invalid credentials.
3. Read and update Pi's native format (`auth.json`: `{provider: {"type":"api_key","key":"..."}}`; `settings.json`: `defaultProvider`, `defaultModel`, `defaultThinkingLevel`). Preserve unknown providers and settings fields. Write with private permissions, atomic replace and serialized backend writes; do not expose keys in responses/logging. Define shell-vs-UI simultaneous edit semantics explicitly (detect a changed file and retry or report conflict, never silently clobber). Each file can be atomic individually; report partial failures rather than claiming a two-file transaction.
4. Keep loopback/capability checks on config endpoints. Remove credential-bearing request fields from catalog/selection/apply endpoints and stop synthesizing provider API key environment variables from Android. Apply selection by restarting both agents only after successful validation; on failure preserve/recover the previous running configuration and report a diagnostic without secrets. Run focused tests to green.

### Task 3: Android settings UI and migration

**Files:** `android/app/src/main/java/com/seed/app/ui/settings/{SettingsScreen.kt,SettingsForm.kt,SettingsViewModel.kt}`, `android/app/src/main/java/com/seed/app/data/{BackendApi.kt,AndroidSettingsRepo.kt,SettingsRepo.kt}`, `android/app/src/main/java/com/seed/app/runtime/{RuntimeService.kt,PiRuntimeEnvironment.kt}`, corresponding `android/app/src/test/java/com/seed/app/{data,runtime,ui/settings}/` tests.

1. Write failing view-model/API tests for three sections: **New provider** (built-in list + key entry), **Select provider** (all configured providers), **Select model** (filtered by selected provider). Switching provider refreshes models; selecting and saving updates Pi defaults. Shell-added providers appear on reload. Saved keys are never hydrated back into the form; blank key does not erase an existing credential.
2. Replace Android key persistence and runtime key injection with backend config calls. Retain unrelated app preferences only. Decide migration explicitly: on first upgrade, either offer a one-time import of the existing encrypted key with user confirmation and erase it after acknowledged backend save, or document manual re-entry; never silently lose the only copy. Test fresh install and upgrade paths.
3. Show actionable provider-auth versus connectivity errors; never render raw RPC JSON as a secret-bearing settings response. Existing chat Middleman JSON display is a separate presentation issue: document it rather than silently expanding this refactor's scope.
4. Run Android unit tests and backend suite. Test on device: add provider/key in Settings; choose provider/model; send chat prompt; switch provider and back; add a provider via Seed terminal `/login` and verify Settings sees it after reload; restart app/runtime and repeat; check redacted failure diagnostics and absence of keys in API/chat logs.

### Commit 1 gate

- Review `git diff` for credentials and unintended pre-existing changes. Commit only implementation/test/docs files for this commit with `git commit -m "refactor: use shared Pi config for provider settings"` after verified tests and on-device validation. If chat still fails, record exact cause and do not claim it fixed.

## Commit 2: Best-effort PRoot guest identity and app permissions

**Technical debt acknowledged:** PRoot guest UIDs/GIDs and guest file modes do **not** create an enforceable boundary from another process with the same Android app UID. A Worker able to execute code may access files outside the intended app directory. This commit organizes normal usage, not secure sandboxing; do not claim protection for `auth.json` or other secrets.

### Task 4: Define guest roles and package layout

**Files:** `scripts/build-runtime.sh`, `android/app/src/main/java/com/seed/app/runtime/RuntimeExtractor.kt`, `backend/seed_backend/flask_manager.py`, `backend/seed_backend/pi_runner.py`, `backend/seed_backend/orchestrator.py`, matching runtime/backend tests.

1. Add failing tests describing effective guest identities for terminal (root), Middleman (read-only app role), Worker (app-edit role), and Flask (app-serving role). Test that Middleman can read but not ordinarily write `/home/seed/app`, and Worker can read/write there. Both must read shared Pi config to function. Specify permissions for runtime-created app files and upgrades, not just packaged files.
2. Add guest users/groups at rootfs build; arrange app-directory ownership/modes and startup reconciliation after extraction, because the current extractor does not restore archive ownership or all permission bits. Keep terminal root and configure Pi/Flask child execution identities without relying on a shell-only `su` command. Preserve required backend, executable, session, and shared Pi config access; do not accidentally let the Worker overwrite Pi credentials through group-write access.
3. Check whether actual `proot`/Android device behavior enforces the expected guest modes. If not, keep the roles as organizational labels and document observed limitations rather than inventing a bypass or reporting a security guarantee.

### Task 5: Verify roles and document debt

**Files:** relevant `android/app/src/androidTest/java/com/seed/app/runtime/` smoke tests; `backend/tests/`; `docs/pi-config.md` or dedicated threat-model note.

1. Run backend and Android tests, then on-device check terminal root access, Middleman/Worker identity and normal app access, Flask startup, both agents authenticating with the shared Pi config, and a chat-generated app edit. Verify runtime upgrade does not reset credentials or make the app unusable.
2. Document shared Android UID/PRoot limitations, agent access to credentials, and future stronger-isolation work as explicit technical debt. Do not describe guest permissions as a sandbox.
3. Review/stage only commit-2 files and `git commit -m "chore: organize guest agent roles and app permissions"` after verification.

## Verification commands (adapt to repository toolchain)

- `cd backend && python -m pytest tests/test_pi_config.py tests/test_pi_control.py tests/test_agent_apply.py tests/test_process_env.py -q`
- `cd backend && python -m pytest -q`
- `cd android && ./gradlew :app:testDebugUnitTest`
- On-device/instrumented smoke tests and real provider connection test are required separately; unit tests cannot prove PRoot permission behavior or network connectivity.
