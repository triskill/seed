# Pi Model Catalog Update Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Refresh stale provider model catalogs from Settings without changing selected models or restarting chat agents.

**Architecture:** Pin bundled Pi to verified 0.84.2 and enforce its Node >=22.19.0 requirement. Add a capability-protected POST `/control/v1/models/update` that executes fixed argv `pi update --models` with the shared app-private `PI_CODING_AGENT_DIR`, a bounded timeout, ignored stdin and suppressed output. Serialize updates with catalog/control operations, invalidate the dedicated control runner, and reload models in Android after success. Keep ordinary GET refresh read-only. No crash investigation, automatic runtime self-updates, build-time authenticated refresh, or history rewrite.

**Tech Stack:** Python/FastAPI/Popen, Pi 0.84.2, Kotlin/Compose/Retrofit, pytest/JVM tests.

## Verified requirements

Installed 0.84.2 documentation/source confirms `pi update --models` is headless and does not self-update or load extensions. It refreshes authenticated provider catalogs, uses `models-store.json` under `PI_CODING_AGENT_DIR`, and can refresh/write OAuth credentials. Exit 0 may be a no-op; partial refresh errors may still update some providers. It has a cooperative 15-second internal timeout; an outer bounded process-group timeout is required. Do not forward diagnostics or assume refreshed catalog means working provider authentication. Existing auth/cache binding already shares the Pi directory with terminal/control/chat. Existing build pins 0.80.3 and checks only Node major 22.

## Task 1: Runtime pin

Files: `scripts/build-runtime.sh`, related runtime-tool tests/docs.

1. Add/adjust regression asserting exact Pi pin and Node minimum version check; verify red.
2. Pin Pi 0.84.2, compare full Node version >=22.19.0, verify `pi update --help` supports models flag during image build. No authenticated update during build.
3. Run shell runtime-tool tests and `bash -n scripts/build-runtime.sh`. Record actual runtime build availability; do not claim generated runtime rebuilt without running it.

## Task 2: Protected update operation

Files: create `backend/seed_backend/model_catalog.py` if separate executor warranted; modify `backend/seed_backend/pi_control.py`, `backend/seed_backend/service.py`; test new executor/control/service cases.

1. TDD success, failed exit, timeout/process cleanup, cancellation, auth rejection, concurrent calls, shared config directory and no agent restart/default setting mutations.
2. Fixed executable derived from existing Pi command wiring (no user argv). Use Popen in a thread for PRoot compatibility, DEVNULL stdin/stdout/stderr, process-group cleanup. Bound update runtime (~25 seconds) and ensure cancelled spawn cannot orphan process. Reuse proven lifecycle ownership patterns where possible.
3. Serialize update and catalog RPC through control lock without recursively acquiring it; reset catalog runner after update, including partial failure, then let subsequent GET create a fresh snapshot. Sanitize all HTTP errors; never return command output. No arbitrary provider/command/body options.
4. Preserve capability authentication and Pi auth access while excluding runtime capability from subprocess environment. Do not add unnecessary credentials to Flask/shell.
5. Run focused backend tests then full Python suite.

## Task 3: Settings action

Files: `android/app/src/main/java/com/seed/app/data/BackendApi.kt`, `ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`, related JVM tests/strings.

1. TDD explicit update API invocation, success reload, failure retaining existing catalog/selection, duplicate-click guard, cancellation propagation, provider change during update and sanitized errors.
2. Add Update models button with progress/disabled state. API POST then reload configuration/model catalog; existing GET refresh remains separate. Preserve selection even if removed from updated catalog; normal validation should explain invalid selection rather than silently choosing another model.
3. Ensure HTTP timeout exceeds backend bound and UI update does not race login/apply actions. Do not change model-apply crash behavior.
4. Run Settings JVM tests and Android lint.

## Implementation status

Backend/runtime/Settings code and regression tests implemented. Local `make verify` passed: 297 Python tests, scoped Ruff/mypy, Android JVM tests and lint. Runtime-tool shell tests and `bash -n scripts/build-runtime.sh` passed. Pi 0.84.2 thinking metadata supports `max` when explicitly mapped; the credential environment boundary includes newly verified provider variables. Runtime image/APK was not rebuilt or installed, and authenticated openai-codex/device acceptance remains pending. Crashes remain explicitly out of scope.

## Task 4: Upgrade compatibility and final verification

Check thinking levels/0.84.2 RPC metadata compatibility for catalog, especially new `max`; update catalog mapping only with supported tests/source evidence. Run `make verify`, runtime-tool shell tests, `git diff --check`. Document need to rebuild/install runtime to deliver Pi upgrade; existing phone rootfs is not changed by source-only edits. Run pinned HEAD secret scan after commit. No provider login or credential printing in tests. A device with openai-codex subscription must verify new catalog population separately; do not claim that acceptance locally.
