# Seed Phase 9 Runtime Startup Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Start and bind the embedded runtime after extraction, block the main navigation until the loopback backend is healthy, and provide startup, failure, retry, and Android notification-permission UX.

**Architecture:** `BootController` remains the owner of extraction state while `RuntimeService` remains the owner of the proot process and backend health. A pure startup resolver keeps navigation gating JVM-testable, and a plain Kotlin runtime supervisor makes retry behavior testable without Android service tests. `MainActivity` is only the lifecycle adapter that observes extraction, starts/binds the service, mirrors binder health, and renders the resolved destination.

**Tech Stack:** Kotlin 1.9, Android foreground/bound services, coroutines and `StateFlow`, Jetpack Compose Material 3, Activity Result API, Retrofit/OkHttp, JUnit 4, Compose instrumentation tests.

---

## Scope decisions

The current Phase 8 implementation supersedes several older notes in `TODO.md` and `docs/plans/2026-07-03-embedded-runtime.md`:

- Keep `BootState` limited to `NeedsExtraction`, `Extracting`, and `Ready`. Runtime startup already has `HealthState.Unknown`, `Polling`, `Healthy`, and `Unhealthy`; duplicating these in `BootState` would create conflicting state owners.
- Start `RuntimeService` from `MainActivity` only after extraction reaches `Ready`. `SeedApp` continues to create the notification channel but must not race extraction by starting the service.
- Treat any successful `/health` response as ready, including `flask="down"`, matching the tested `HealthMonitor` contract.
- Flip the fixed defaults to `127.0.0.1`. Do not add a settings `host` field in Phase 9: persisting a host without rebuilding the lazy Retrofit, WebSocket, and WebView clients would expose a control that does not take effect reliably.
- Keep runtime ports fixed at 7777/7778. Runtime restart on port changes is outside this phase.
- Do not add automatic crash supervision, runtime controls, App-screen reload, Shell cancellation, or status pills; those remain Phase 10 work.

## Task 1: Pin application endpoint defaults to loopback

**Files:**
- Create: `android/app/src/test/java/com/seed/app/data/EndpointDefaultsTest.kt`
- Modify: `android/app/build.gradle.kts`
- Modify documentation comments in: `android/app/src/main/java/com/seed/app/data/ApiModule.kt`
- Modify documentation comments in: `android/app/src/main/java/com/seed/app/ui/app/WebViewConfig.kt`

**Step 1: Write the failing test**

Add a JVM test that asserts:

```kotlin
assertEquals("http://127.0.0.1:7777/", BuildConfig.BACKEND_DEV_URL)
assertEquals("http://127.0.0.1:7778/", BuildConfig.WEBAPP_DEV_URL)
assertTrue(WebViewConfig.isAllowedUrl(BuildConfig.WEBAPP_DEV_URL))
```

**Step 2: Verify RED**

```bash
cd android
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests com.seed.app.data.EndpointDefaultsTest
```

Expected: assertions fail because both generated constants still use `10.0.2.2`.

**Step 3: Implement the minimum change**

Change the two `buildConfigField` values to:

```kotlin
buildConfigField("String", "WEBAPP_DEV_URL", "\"http://127.0.0.1:7778/\"")
buildConfigField("String", "BACKEND_DEV_URL", "\"http://127.0.0.1:7777/\"")
```

Keep `10.0.2.2` in the WebView and network-security allowlists for host development, but stop documenting it as the default.

**Step 4: Verify GREEN**

Run the focused test again and expect PASS.

**Step 5: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/java/com/seed/app/data/ApiModule.kt \
  android/app/src/main/java/com/seed/app/ui/app/WebViewConfig.kt \
  android/app/src/test/java/com/seed/app/data/EndpointDefaultsTest.kt
git commit -m "feat(android): use embedded loopback endpoints by default"
```

## Task 2: Add a retryable runtime supervisor

**Files:**
- Create: `android/app/src/main/java/com/seed/app/runtime/RuntimeSupervisor.kt`
- Create: `android/app/src/test/java/com/seed/app/runtime/RuntimeSupervisorTest.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/RuntimeService.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/RuntimeBinder.kt`

**Step 1: Write failing supervisor tests**

Use fake `ProotHandle` instances and canned health flows to cover:

1. First `startOrRetry()` starts one process and republishes health.
2. Retry with a live process reuses it but starts a fresh health probe.
3. Retry after process death destroys the stale handle and starts another.
4. Process-start failure becomes `HealthState.Unhealthy`.
5. Unexpected health-flow failure becomes `Unhealthy`; cancellation remains cancellation.
6. `stop()` cancels polling and destroys the active handle once.

The desired API is:

```kotlin
internal class RuntimeSupervisor(
    private val scope: CoroutineScope,
    private val startProcess: () -> ProotHandle,
    private val healthStates: () -> Flow<HealthState>,
) {
    val health: StateFlow<HealthState>
    val isRuntimeAlive: Boolean
    fun startOrRetry()
    fun stop()
}
```

**Step 2: Verify RED**

```bash
cd android
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests com.seed.app.runtime.RuntimeSupervisorTest
```

Expected: compile failure because `RuntimeSupervisor` does not exist.

**Step 3: Implement minimal supervisor behavior**

- Cancel the previous health collection on each `startOrRetry()`.
- Reset published health to `Unknown`.
- Reuse an existing live handle.
- Destroy and replace a dead handle.
- Convert process-start and non-cancellation health failures to `Unhealthy`.
- Start a fresh collection from `healthStates()`.
- Make `stop()` idempotently cancel polling and destroy the handle.

**Step 4: Integrate the service and binder**

Refactor `RuntimeService` to construct one supervisor with:

```kotlin
startProcess = { runner.start(serviceScope).also(::collectRuntimeLogs) }
healthStates = { HealthMonitor(ApiModule.embedded).states() }
```

Call `startOrRetry()` after `startForeground`. Delegate binder health/liveness/retry to the supervisor. Preserve `RuntimeBinder.stop()` and add `retry()`.

**Step 5: Verify GREEN and regressions**

```bash
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest \
  --tests com.seed.app.runtime.RuntimeSupervisorTest \
  --tests com.seed.app.runtime.HealthMonitorTest \
  --tests com.seed.app.runtime.ProotRunnerTest
```

Expected: PASS.

**Step 6: Commit**

```bash
git add android/app/src/main/java/com/seed/app/runtime/RuntimeSupervisor.kt \
  android/app/src/main/java/com/seed/app/runtime/RuntimeService.kt \
  android/app/src/main/java/com/seed/app/runtime/RuntimeBinder.kt \
  android/app/src/test/java/com/seed/app/runtime/RuntimeSupervisorTest.kt
git commit -m "feat(android): add retryable runtime supervisor"
```

## Task 3: Model startup gating as pure Kotlin

**Files:**
- Create: `android/app/src/main/java/com/seed/app/runtime/RuntimeStartup.kt`
- Create: `android/app/src/test/java/com/seed/app/runtime/RuntimeStartupTest.kt`

**Step 1: Write failing tests**

Cover these contracts:

- `NeedsExtraction` and `Extracting` never invoke the ready callback.
- The first `Ready` invokes it once; repeated `Ready` updates do not duplicate start/bind.
- Extraction UI wins over stale runtime health.
- `Ready + Unknown/Polling/Unhealthy` resolves to runtime startup UI.
- Only `Ready + Healthy` resolves to the main navigation.
- Notification permission is requested only on API 33+, when ungranted and never requested.

Desired API:

```kotlin
internal sealed interface StartupDestination {
    data class Extraction(val state: BootState) : StartupDestination
    data class Runtime(val health: HealthState) : StartupDestination
    data object Seed : StartupDestination
}

internal fun resolveStartupDestination(
    boot: BootState,
    health: HealthState,
): StartupDestination

internal class RuntimeStartupGate(private val onReady: () -> Unit) {
    fun update(boot: BootState)
}

internal fun shouldRequestRuntimeNotificationPermission(
    sdkInt: Int,
    granted: Boolean,
    alreadyRequested: Boolean,
): Boolean
```

**Step 2: Verify RED**

Run `RuntimeStartupTest`; expect compile failure.

**Step 3: Implement the pure model**

Implement only the behavior pinned by the tests. Do not modify `BootState` or `BootController`.

**Step 4: Verify GREEN**

Run `RuntimeStartupTest`; expect PASS.

**Step 5: Commit**

```bash
git add android/app/src/main/java/com/seed/app/runtime/RuntimeStartup.kt \
  android/app/src/test/java/com/seed/app/runtime/RuntimeStartupTest.kt
git commit -m "feat(android): model runtime startup gating"
```

## Task 4: Add runtime startup and retry UI

**Files:**
- Create: `android/app/src/main/java/com/seed/app/runtime/StartRuntimeScreen.kt`
- Create: `android/app/src/androidTest/java/com/seed/app/runtime/StartRuntimeScreenTest.kt`
- Modify: `android/app/src/main/res/values/strings.xml`
- Modify: `android/app/build.gradle.kts` only if the instrumentation runner is absent

**Step 1: Write the failing Compose tests**

Test that:

- `Unknown` and `Polling` show a startup title and progress indicator, not Retry.
- `Polling` shows its attempt number.
- `Unhealthy(message)` shows the message and Retry.
- Clicking Retry invokes the callback once.

Use stable tags: `runtime-start-screen`, `runtime-start-progress`, `runtime-start-error`, and `runtime-start-retry`.

**Step 2: Verify RED**

```bash
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:compileDebugAndroidTestKotlin
```

Expected: compile failure because `StartRuntimeScreen` is absent.

**Step 3: Implement minimal Compose UI**

Create:

```kotlin
@Composable
fun StartRuntimeScreen(
    health: HealthState,
    onRetry: () -> Unit,
)
```

Render `Unknown`/`Polling` as a centered spinner and caption. Render `Unhealthy` as an error-colored banner plus Retry button. Render `Healthy` as a brief spinner fallback because the parent immediately switches destinations.

**Step 4: Verify compilation and connected test when available**

```bash
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:compileDebugAndroidTestKotlin
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.seed.app.runtime.StartRuntimeScreenTest
```

The connected command requires an emulator/device; compilation is mandatory even when none is available.

**Step 5: Commit**

```bash
git add android/app/build.gradle.kts android/app/src/main/res/values/strings.xml \
  android/app/src/main/java/com/seed/app/runtime/StartRuntimeScreen.kt \
  android/app/src/androidTest/java/com/seed/app/runtime/StartRuntimeScreenTest.kt
git commit -m "feat(android): add runtime startup and retry screen"
```

## Task 5: Wire MainActivity to extraction, service, and health

**Files:**
- Modify: `android/app/src/main/java/com/seed/app/MainActivity.kt`

**Step 1: Use the tested seams**

Keep these Activity-owned fields:

- `MutableStateFlow<HealthState>(Unknown)` for Compose.
- Current nullable `RuntimeBinder`.
- Nullable binder-health collection `Job`.
- Boolean tracking whether `bindService` returned true, including the pre-connection interval.
- One `ServiceConnection`.
- One `RuntimeStartupGate`.
- One Activity Result launcher for `POST_NOTIFICATIONS`.

**Step 2: Observe boot outside composition**

Construct `BootController` once in `onCreate`. Collect its states in `lifecycleScope`:

- Call idempotent `runExtraction()` for `NeedsExtraction`.
- Pass every state to `RuntimeStartupGate.update`.
- Start/bind only on the first `Ready`.

Compose renders state only and performs no service side effects.

**Step 3: Implement start/bind lifecycle**

`startAndBindRuntime()` must:

1. Reset local health to `Unknown`.
2. Call `ContextCompat.startForegroundService` with an explicit intent.
3. Call `bindService(..., BIND_AUTO_CREATE)` and record its Boolean result.
4. Convert startup/binding rejection into `Unhealthy`.
5. Request `POST_NOTIFICATIONS` once on API 33+ without gating service startup.

The `ServiceConnection` safely casts the binder, mirrors `binder.health` in `lifecycleScope`, and reports disconnect/null/dead bindings as actionable `Unhealthy` states.

Retry calls `binder.retry()` when connected; otherwise it releases stale binding state and repeats start/bind.

Do not unbind in `onStop`. In `onDestroy`, cancel collection and unbind exactly once if binding was accepted. Do not stop the foreground service.

**Step 4: Render only through the resolver**

```kotlin
when (val destination = resolveStartupDestination(bootState, runtimeHealth)) {
    is StartupDestination.Extraction -> ExtractionScreen(destination.state)
    is StartupDestination.Runtime -> StartRuntimeScreen(destination.health, ::retryRuntime)
    StartupDestination.Seed -> SeedNav()
}
```

**Step 5: Verify build and tests**

```bash
cd android
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:compileDebugAndroidTestKotlin
```

Expected: all tasks succeed.

**Step 6: Commit**

```bash
git add android/app/src/main/java/com/seed/app/MainActivity.kt
git commit -m "feat(android): start runtime after extraction"
```

## Task 6: Documentation and final verification

**Files:**
- Modify: `TODO.md`
- Modify: `docs/plans/2026-07-03-embedded-runtime.md`
- Modify: `android/README.md`

**Step 1: Update documentation**

Record the implemented ownership model, retry behavior, loopback defaults, API 33 permission request, and tests. Mark Phase 9 complete only after automated verification. Explicitly mark the older `BootState.Starting/RuntimeError` and settings-host proposals as superseded.

Document that ignored runtime assets are absent from a fresh worktree and must be generated with `scripts/build-runtime.sh`. The bundled proot is arm64, so a real runtime test requires an arm64 device/emulator; an x86_64 emulator can only verify Compose UI.

**Step 2: Run all verification**

```bash
cd android
ANDROID_HOME=$HOME/android-sdk ./gradlew :app:testDebugUnitTest :app:assembleDebug \
  :app:lintDebug :app:compileDebugAndroidTestKotlin

cd ..
/home/borbot/prg/seed/.venv/bin/python -m pytest backend webapp -q
```

Expected: Android unit tests pass, APK assembles, lint has no errors, Android-test sources compile, and all 107 backend/webapp tests pass.

If an emulator is running, also run the focused Compose instrumentation test. Do not claim embedded-runtime end-to-end verification unless the ignored assets have been generated and an arm64 target is available.

**Step 3: Commit**

```bash
git add TODO.md docs/plans/2026-07-03-embedded-runtime.md android/README.md
git commit -m "docs(android): complete Phase 9 runtime startup"
```

## Real-device acceptance checklist

After generating runtime assets and connecting an arm64 target:

1. First launch shows extraction UI and does not start `RuntimeService` during extraction.
2. After extraction reaches `Ready`, the service starts, binds, spawns proot, and displays polling UI.
3. Navigation remains hidden for `Unknown`, `Polling`, and `Unhealthy`.
4. Navigation appears after backend health becomes `Healthy`.
5. Denying notification permission does not prevent startup.
6. App WebView loads `http://127.0.0.1:7778/`; Shell and Chat reach `127.0.0.1:7777`.
7. Retry re-probes a live process and replaces a dead process without duplicating live proot instances.
8. Backgrounding the Activity keeps the foreground service alive.
9. Activity destruction unbinds without stopping the service.
10. Force-stop terminates the service and proot.
