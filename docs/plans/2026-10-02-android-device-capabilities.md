# Android Device Capabilities Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Give generated apps one asynchronous JSON interface to approved Android capabilities, documented in both agent system prompts.

**Architecture:** An origin-bound WebMessage bridge injects `seed.android.call({method, params})` at document start. A strict registry validates a versioned request and dispatches to native adapters without arbitrary reflection. Shared user consent, error handling, concurrency limits and lifecycle cleanup protect native operations.

**Tech Stack:** Kotlin/Compose, AndroidX WebKit already installed, Moshi, coroutines, platform SensorManager and ActivityResult camera intent; plain browser JavaScript.

**Status:** First slice implemented. 303 JVM / 12 SDK / 296 backend tests, lint and APK builds passed; Moto G32 full connected suite passed 21/21. Installed prompt hashes match source; settings/generated-app hashes unchanged. Actual user-operated camera capture and generated-app feature acceptance remain pending. See `docs/reports/2026-10-02-android-device-capabilities.md`.

## Approved direction and first slice

User approved a generic JSON protocol with an explicit capability registry instead of arbitrary Android reflection. Initial concrete methods: `capabilities.list`, `camera.capture`, `sensor.list`, `sensor.read`. Sensor read is a bounded one-shot measurement for any available sensor type, not streaming yet. Camera uses the system camera app and returns a bounded JPEG preview/data URL; full-resolution/video/GPS/streams are follow-ups. Never replace the user's generated app or clear its storage.

Transport requires both WEB_MESSAGE_LISTENER and DOCUMENT_START_SCRIPT. No insecure legacy fallback. Restrict to the exact configured app origin (including port), top frame and current page; no wildcard or backend port privileges. Allowlist schemas and bounded requests, one active operation, structured errors, bounded waits, native confirmation for sensor/camera requests. Navigation/disposal cancels pending requests; camera late results cannot satisfy a later call.

## Task 1: Protocol and secure transport

Create `android/app/src/main/java/com/seed/app/device/DeviceProtocol.kt`, `DeviceWebBridge.kt`, and `android/app/src/main/assets/device/seed-android.js`.

Shared host interface: `DeviceCapabilityHost` has `suspend fun invoke(method: String, params: Map<String, Any?>): Map<String, Any?>` and `fun close()`. `DeviceCapabilityError(code, message)` is the structured native error. Bridge accepts a WebView, host, and expected URL; exposes `onNavigation()` and `close()`.

Wire format: request `{v:1,id,method,params}`; reply `{v:1,id,ok,result}` or `{v:1,id,ok:false,error:{code,message}}`. ID bounded and validated; input <=8KiB, strict object/parameter keys, positive integral sensor type, timeoutMs 100..10000 (default 3000). Camera/list params empty. Capability list includes schemas and limitations. Native handler returns JSON-safe maps. Cancellation propagates, timeout/failure gives structured errors, concurrent operation BUSY. Browser call adds IDs, correlates promises, enforces limits/timeouts and page-disposal rejection.

Write failing JVM/JS tests first, run focused tests, implement, rerun. Cover origins/ports/userinfo/frames, malformed requests, schemas, unknown methods, result/error, timeout, BUSY, cancellation and browser promise correlation. Node tests use built-in `node:test`/vm, no packages.

## Task 2: Native adapters and App tab wiring

Create `AndroidDeviceCapabilities.kt`, returning a remembered Compose host with an explicit confirmation dialog and ActivityResult TakePicturePreview. Handle cancellation, no camera handler, busy camera slot, late results, capped image size; no filesystem/provider secrets in results. Add camera intent visibility query to manifest, no broad storage/device permissions. Use SensorManager TYPE_ALL, one-shot listener with timeout and unregister on every completion/cancellation path. No per-sensor wrappers; unsupported/restricted sensors yield errors.

Modify `ui/app/AppScreen.kt`: remember host and bridge with WebView, install before loadUrl, notify navigation start, close bridge before destroying WebView. Preserve DOM storage and existing generated app. Write failing native instrumentation tests before implementing adapters; use test-only host/permission seams and real sensor smoke where feasible. Never automate taking a user's photograph.

## Task 3: Agent instructions and acceptance

Update `backend/prompts/middleman.md` and `worker.md` with methods, schemas, examples, permission/error UX, host fallback, image preview limitation, no backend/CLI invocation, no claim of device acceptance from curl. Do not change bundled generated-app files. Add contract checks for prompt/API drift. Bundle only these two prompts through the Gradle `bundleAgentPrompts` Sync task into APK `agent-prompts/` assets. Before starting the backend, `runtime/AgentPromptInstaller.kt` validates both assets and atomically replaces only the two installed role prompts. Test preservation of generated app/credentials and rejection of symlinked prompt directories; no rootfs re-extraction or marker change is necessary.

Run `cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain`; run Node SDK tests and prompt checks. Review security/lifecycle changes, fix with regression tests. Device test bridge with isolated HTML/test host, origin/frame denial and sensor path, then existing connected regressions. Photo capture requires explicit user-operated camera acceptance, otherwise label it pending. Install only with -r; retain settings, app, and browser data. Leave pre-existing `assets/linux/seed_version.json` untouched and uncommitted.
