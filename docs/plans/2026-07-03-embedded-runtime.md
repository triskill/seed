# Embedded runtime: run Alpine inside the Android app

**Date:** 2026-07-03
**Status:** implemented through Phase 9 on 2026-07-30; arm64 device acceptance remains manual
**Owner:** seed-dev
**Goal:** the existing Phase 7/8/9 plan finally lands end-to-end — the Seed
APK boots an `Alpine + Python + pi` runtime inside the device and the
four screens talk to it on `127.0.0.1:7777`, no host dev stack required.

---

## 1. Background and current state

The v0.1 bootstrap plan (`docs/plans/2026-06-30-seed-v0.1-bootstrap.md`)
defined a three-layer architecture: Android shell → embedded Linux
runtime (proot + Alpine) → webapp. The runtime layer was scoped across
**Phase 7** (extraction), **Phase 8** (foreground service), and
**Phase 9** (runtime startup gate). Current implementation status:

| Component | Status |
|---|---|
| `scripts/build-runtime.sh` (Docker arm64 build) | ✅ implemented and exercised; generated binaries remain gitignored |
| `assets/linux/proot` + `rootfs.tar.gz` | ✅ generated locally when needed; absent from fresh clones/worktrees until the build script runs |
| `assets/linux/seed_version.json` | ✅ tracked |
| Runtime extraction and versioning | ✅ implemented, traversal-hardened, and JVM-tested |
| `ProotRunner` / `HealthMonitor` / `RuntimeService` | ✅ implemented and JVM-tested at the process/health seams |
| Foreground-service manifest, notification channel, and icon | ✅ implemented |
| Runtime start ownership | ✅ `MainActivity` starts/binds only after extraction; `SeedApp` creates the channel only |
| Retry | ✅ `RuntimeSupervisor` re-polls a live process or replaces a dead process |
| Client defaults | ✅ fixed loopback defaults at `127.0.0.1:7777/7778` |
| Settings host model | ✅ defaults to and persists `127.0.0.1`; dynamic client rebuilding is deferred |
| Rootfs entrypoint | ✅ hard-coded in `ProotRunner` as the v0.1 launch command |

The implementation deliberately keeps extraction `BootState` and runtime
`HealthState` as separate owners. A pure `RuntimeStartup` resolver combines
them for UI gating instead of adding duplicate `BootState.Starting` and
`BootState.RuntimeError` variants proposed earlier in this document. The
arm64 runtime acceptance checklist in §6 remains a manual device step.

---

## 2. Target architecture

### 2.1 Process layout (after this work lands)

```
┌─── Android device ─────────────────────────────────────────────────┐
│                                                                     │
│  com.seed.app (uid u0_a141)                                         │
│   ├── MainActivity  ── binds ──▶ RuntimeService                     │
│   │                                  │                              │
│   │                                  │ startForeground              │
│   │                                  ▼                              │
│   │                            ProotRunner                          │
│   │                                  │                              │
│   │                                  ▼                              │
│   │                            proot -r files/linux/rootfs \         │
│   │                                 /bin/sh -c "cd /home/seed/      │
│   │                                   backend && exec python -m     │
│   │                                   seed_backend.service"        │
│   │                                  │                              │
│   │                                  ▼                              │
│   │                            python: uvicorn (PID inside proot)   │
│   │                            ├─▶ Flask webapp subprocess :7778   │
│   │                            └─▶ pi middleman/worker procs        │
│   │                                                                 │
│   ├── BackendApi → http://127.0.0.1:7777/  (same loopback)          │
│   ├── WebView   → http://127.0.0.1:7778/  (same loopback)          │
│   └── HealthMonitor polls http://127.0.0.1:7777/health              │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

Proot shares the network namespace by default, so `127.0.0.1:7777` and
`127.0.0.1:7778` inside the proot process are reachable from the parent
Android process without `adb reverse` or `10.0.2.2`. This is the
critical simplification that makes the embedded path work.

### 2.2 File layout (after this work lands)

New files:

```
android/app/src/main/java/com/seed/app/runtime/
├── ProotRunner.kt            # Task 8.1 — spawns proot, captures stdout/stderr
├── RuntimeService.kt         # Task 8.2 — foreground service, owns ProotRunner
├── RuntimeBinder.kt          # Task 8.2 — binder IPC for ViewModels
├── HealthMonitor.kt          # Task 8.3 — polls /health, exposes StateFlow
└── StartRuntimeScreen.kt     # Task 9.1 — "Starting runtime…" UI

android/app/src/test/java/com/seed/app/runtime/
├── ProotRunnerTest.kt        # JVM unit tests using a fake Process
├── HealthMonitorTest.kt      # JVM unit tests using a fake BackendApi
└── RuntimeServiceTest.kt     # Robolectric tests (NOT v0.1 — defer)
```

Modified files:

```
android/app/src/main/AndroidManifest.xml            # FOREGROUND_SERVICE, <service>
android/app/src/main/java/com/seed/app/SeedApp.kt   # create runtime notification channel
android/app/src/main/java/com/seed/app/MainActivity.kt  # start/bind RuntimeService after extraction; gate UI on health
android/app/src/main/java/com/seed/app/data/BackendApi.kt  # default URL 10.0.2.2:7777 → 127.0.0.1:7777
android/app/src/main/java/com/seed/app/ui/settings/SettingsForm.kt  # add `host` field (127.0.0.1 / 10.0.2.2)
android/app/build.gradle.kts                       # backport noCompress (already in working tree)
```

### 2.3 Rootfs entrypoint

The current `scripts/build-runtime.sh` Dockerfile installs deps and
copies the backend + webapp into `/home/seed/` but has no entrypoint.
For the embedded path we have two options:

| Option | Pros | Cons |
|---|---|---|
| **A. Hard-code the launch in `ProotRunner.kt`**: `proot -r ... /bin/sh -c "cd /home/seed/backend && exec python -m seed_backend.service"` | One Kotlin line, easy to grep, no extra files in the rootfs | Future "run this script" additions require APK rebuild |
| **B. Add `/usr/local/bin/seed-start` to the Dockerfile, have `ProotRunner.kt` call it** | Future scripts (e.g. `seed-shell`) don't need APK changes | One more file in the rootfs, more moving parts |

**Decision: A.** v0.1 is a single entrypoint; the simplicity wins.
We do **not** add `--reload` to uvicorn (the dev.sh has it for
host-side iteration; in the embedded runtime there's no source
watcher, and the reload-spawned child process is unwanted inside
proot). The embedded command is:

```sh
exec uvicorn seed_backend.service:app --host 127.0.0.1 --port 7777
```

(matches `dev.sh` minus `--reload`; `exec` is so uvicorn receives
signals directly from proot, same pattern as the host script).

### 2.4 Dev-mode vs embedded-mode toggle

The current build wires `BACKEND_DEV_URL=http://10.0.2.2:7777/` as a
`buildConfigField` and `BackendApi` reads it at construction time.
Two options for the embedded path:

| Option | Pros | Cons |
|---|---|---|
| **A. Flip the default to `127.0.0.1:7777` in the `buildConfigField`, keep the same mechanism** | One-line change, existing tests still work via build flavor | No way to talk to a host backend from a release APK |
| **B. Add a `host` field to `SettingsForm` (already a data class) so the user can pick `127.0.0.1` (embedded) or `10.0.2.2` (host dev) at runtime** | Toggle in-app, no rebuild needed | Slightly more code, more tests |

**Implemented decision: staged B.** Fixed build defaults now point to
`127.0.0.1`, and `SettingsForm.host` defaults to and persists that value.
The host is intentionally not exposed or applied to already-created Retrofit,
WebSocket, and WebView clients yet. Phase 10 must rebuild all three client types
atomically before a "Connect to dev backend" toggle can be truthful. The
embedded service also keeps ports 7777/7778 fixed for v0.1.

### 2.5 The "Starting runtime…" UX

`BootState` is currently `{NeedsExtraction, Extracting(progress),
Ready}`. The current `MainActivity` shows `ExtractionScreen` while
`!Ready` and `SeedNav` once `Ready`. The embedded runtime needs a
new state between "rootfs on disk" and "backend reachable":

```
NeedsExtraction ──extract──▶ Extracting(progress)
                              └─▶ Ready
                                   └──start service──▶ RuntimeStarting
                                                        └─▶ RuntimeHealthy
                                                             └──▶ SeedNav
```

The implementation keeps the two existing state owners separate:

- `BootState` describes only extraction (`NeedsExtraction`, `Extracting`,
  `Ready`).
- `HealthState` describes runtime startup (`Unknown`, `Polling`, `Healthy`,
  `Unhealthy`).
- `resolveStartupDestination(boot, health)` maps those values to
  `ExtractionScreen`, `StartRuntimeScreen`, or `SeedNav`.

This avoids contradictory transitions between the extraction controller and
foreground service. `StartRuntimeScreen` shows a progress indicator and attempt
count for polling, then an error banner and Retry action for unhealthy state.

---

## 3. Task breakdown (ordered)

Each task is sized to fit a single focused commit.

### Task 1 — Preflight (no code)
**Verify before we start:** proot static URL is alive (✅ confirmed
2026-07-03: `https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-aarch64-static` returns 302), Alpine minirootfs is alive
(✅: `https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz` returns 200), docker
arm64 binfmt is enabled on this machine (✅: `qemu-aarch64` listed
in `/proc/sys/fs/binfmt_misc/` and `enabled`).

### Task 2 — Build the runtime assets (Phase 7.2)
Run `./scripts/build-runtime.sh`. The script is already in the
repo, has a trap-based cleanup, and SHA-pins the Alpine minirootfs.
Expected output in `android/app/src/main/assets/linux/`:
- `proot` (aarch64 ELF, ~2 MB)
- `rootfs.tar.gz` (~150 MB)
- `seed_version.json` (overwritten with a new `build_id`)

**Verify:** `file proot` shows `aarch64`; `tar -tzf rootfs.tar.gz`
contains `./home/seed/backend/`, `./home/seed/app/`,
`./usr/bin/python3`, `./usr/bin/node`, `./usr/local/bin/pi`.

**Open question to answer with the first build:** does
`python -m seed_backend.service` actually start inside the
arm64 rootfs? We can answer this by `docker run --rm --platform
linux/arm64 seed-runtime:test python -m seed_backend.service` and
seeing whether it prints `Uvicorn running on ...`. If not, the
`proot -r` command in `ProotRunner.kt` won't work either, and we
need to fix the rootfs before writing any Kotlin.

### Task 3 — `ProotRunner.kt` (Phase 8.1)
**Public surface:**
```kotlin
class ProotRunner(
    private val rootfsDir: File,
    private val prootExecutable: File,
    private val logcat: (String, String) -> Int = { _, msg -> android.util.Log.i("ProotRunner", msg) },
) {
    fun start(scope: CoroutineScope): ProotHandle
    fun stop()
}
interface ProotHandle {
    val pid: Int
    val isAlive: Boolean
    val stdout: Flow<String>   // line-buffered, for future log streaming
    val stderr: Flow<String>
    fun kill()
}
```

Implementation: uses `ProcessBuilder("/path/to/proot", "-r",
"$rootfsDir", "-0", ...)` — `ProcessBuilder` is in the stdlib, so
no new deps. Lines are split on `\n` and emitted on
`Dispatchers.IO`. Kill is `Process.destroy()` followed by
`destroyForcibly()` after a 5-second grace period.

**Note on `-0`:** proot's `-0` is "no special privileges" — the
static proot binary is fine to use unprivileged, and `-0` skips the
`ptrace` escalation attempt that some emulators refuse. Confirm
during the first integration test on the emulator; if the build
works without it, drop it.

**Unit tests (JVM):** mock `Process` via an interface
(`ProcessFactory`) injected into the constructor. Cover: argument
construction (path, args, cwd, env), stdout line splitting, kill
ordering, double-kill is a no-op.

### Task 4 — `HealthMonitor.kt` (Phase 8.3)
**Public surface:**
```kotlin
sealed class HealthState {
    object Unknown : HealthState()
    data class Polling(val attempt: Int) : HealthState()
    data class Healthy(val flask: String) : HealthState()
    data class Unhealthy(val message: String) : HealthState()
}
class HealthMonitor(
    private val api: BackendApi,
    private val intervalMs: Long = 500,
    private val maxAttempts: Int = 60,   // 30 seconds total
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun state(): Flow<HealthState>
    fun stop()
}
```

Implementation: a `flow { while (true) { ... } }` that polls
`api.health()` on a `withTimeout(intervalMs)`. On success emit
`Healthy(flask)`, on failure emit `Polling(attempt)` until
`maxAttempts`, then `Unhealthy("...")`.

**Unit tests (JVM):** fake `BackendApi` returns canned `HealthResponse`
or throws; cover happy path, flask=`"down"` (still `Healthy` —
backend is up, just webapp not yet), timeout, max attempts
exceeded.

### Task 5 — `RuntimeService.kt` + `RuntimeBinder.kt` (Phase 8.2)
`RuntimeService : Service`. In `onCreate`:
1. Build `ProotRunner(rootfs, prootExec)`.
2. Create a `SupervisorJob` scope tied to the service lifetime.
3. Create a `HealthMonitor(BackendApi(...))`.
4. `startForeground(NOTIF_ID, notification)` — `foregroundServiceType="dataSync"`.
5. `proot.start(scope)` — spawns the process.
6. Launch a coroutine that collects `health.state()` and republishes
   it as a `MutableStateFlow<HealthState>` in the binder.

`RuntimeBinder` is a plain `Binder` subclass exposing
`prootPid: Int?`, `health: StateFlow<HealthState>`, `stop()`.

`onDestroy`: `proot.stop()`, `healthMonitor.stop()`, cancel the scope.

**Unit tests:** defer to a future task (Robolectric isn't wired up
yet; v0.1 ships a tested `ProotRunner` + `HealthMonitor` and verifies
the wiring with a real-emulator integration test instead).

### Task 6 — `AndroidManifest.xml` changes
Add to `manifest`:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

Add inside `application`:
```xml
<service
    android:name=".runtime.RuntimeService"
    android:foregroundServiceType="dataSync"
    android:exported="false" />
```

Add a simple notification icon (`res/drawable/ic_stat_seed.xml` —
24dp monochrome vector, Material `ic_info_outline` style).

`SeedApp.onCreate`:
- API 26+ (already minSdk 26): `NotificationChannel("seed_runtime", "Seed runtime", IMPORTANCE_LOW).create()`.
- API 33+: request `POST_NOTIFICATIONS` once on first launch (the
  Settings screen is a natural place; for v0.1 the request is fired
  from `MainActivity.onCreate` and the result is ignored — the
  service runs either way, it just shows a notification if granted).

### Task 7 — `MainActivity` wiring (Phase 9.1)
`BootController` continues to own extraction. When `state` becomes
`Ready`, we now also:
1. `ContextCompat.startForegroundService(this, Intent(this, RuntimeService::class.java))`.
2. `bindService(...)` and collect `binder.health` into a local
   `StateFlow<HealthState>`.
3. The Compose layer branches:
   - `Ready && HealthState.Healthy` → `SeedNav`.
   - `Ready && !Healthy` → `StartRuntimeScreen(state)`.
   - `Ready && HealthState.Unhealthy(msg)` → `StartRuntimeScreen(state)` with a retry button.
4. On `onStop`: keep the service alive (the whole point of a
   foreground service is to survive the activity).
5. On `onDestroy`: `unbindService`.

### Task 8 — Client default URL flip and future host model
- `BuildConfig.BACKEND_DEV_URL` becomes `"http://127.0.0.1:7777/"` (was `"http://10.0.2.2:7777/"`).
- `BuildConfig.WEBAPP_DEV_URL` becomes `"http://127.0.0.1:7778/"`.
- HTTP, WebSocket, and WebView production defaults all consume those fixed
  build constants.
- `SettingsForm` gains and persists `host: String = "127.0.0.1"`; older saved
  forms migrate to that value when the key is absent.
- `10.0.2.2` remains allowed by the WebView and cleartext-security filters.

Dynamic host activation is deferred: changing the stored field must eventually
rebuild Retrofit, WebSocket, and WebView clients together, and embedded runtime
ports remain fixed at 7777/7778. Phase 9 does not expose a control that would
appear to work while leaving existing clients connected to stale endpoints.

### Task 9 — First integration test on the emulator
- Rebuild the APK with the new code + the freshly built `proot` +
  `rootfs.tar.gz` assets.
- `make run` (emulator + install + launch).
- Watch logcat for: `ProotRunner: spawn proot -r ...` →
  `seed_backend: Uvicorn running on http://127.0.0.1:7777` →
  `HealthMonitor: Healthy(flask=up)`.
- In the Shell screen, type `ls` and verify a real directory
  listing returns (not a `ConnectException`).
- In the App screen, verify the WebView loads
  `http://127.0.0.1:7778/` and shows the Flask app.
- `adb shell am force-stop com.seed.app` and verify the proot
  process **stops** (foreground service is tied to the app; when
  the app is force-stopped, the service is too).

### Task 10 — Commit history
Suggested commit order (each independently `assembleDebug`-able):
1. `chore(android): backport noCompress for assets/linux/**` (already in working tree)
2. `docs: design for embedded runtime (this file)`
3. `chore(android): add runtime assets (proot, rootfs.tar.gz)` — **but see below**
4. `feat(android): ProotRunner with stdout/stderr line streams`
5. `feat(android): HealthMonitor with /health polling`
6. `feat(android): RuntimeService foreground + notification`
7. `feat(android): wire BootController → RuntimeService in MainActivity`
8. `feat(android): flip BackendApi/WebView default URL to 127.0.0.1`
9. `feat(android): StartRuntimeScreen for boot/healthy/error states`
10. `docs(android): README — embedded runtime is the default, dev backend via Settings`

**Asset commits (3) are tricky:** the rootfs is ~150 MB. The
existing `.gitignore` already excludes `proot` and `rootfs.tar.gz`
in `assets/linux/` (verify during Task 2 — if it doesn't, add the
lines). The committed artifact is **`seed_version.json`** + the
**build script**; the assets are produced locally and the APK is
built from the local copy. The CI story is "run
`./scripts/build-runtime.sh` before `./gradlew assembleDebug`."

---

## 4. Out of scope (deferred)

These are real concerns but not part of "make it work":

- **Real cancel for `/shell/exec`** (Phase 10) — the Phase 6.4
  `cancel()` stub is unchanged.
- **Stop/Restart/Wipe buttons in Settings** (Phase 9 polish) — the
  Settings screen stays as-is, no runtime controls in v0.1.
- **Health status pill in the App bar** (Phase 10) — not v0.1.
- **Splitting the rootfs into a separate `.obb` to shrink the APK**
  (noted as a future concern in the v0.1 bootstrap plan, line 215).
- **HTTPS / loopback-only TLS** (mentioned in the `BackendApi`
  KDoc, deferred to Phase 7+).
- **Notification icon design** beyond a placeholder monochrome
  vector.
- **Multi-process proot supervision / auto-restart on crash** —
  the foreground service kills proot on `onDestroy`, but a
  crashed proot stays crashed until the user kills and relaunches
  the app. Acceptable for v0.1 "yolo mode" but worth a follow-up
  task.

---

## 5. Open decisions (to make during implementation)

1. **Drop proot's `-0` flag?** Try without first; proot's default
   behaviour on Android is usually fine, and `-0` may be a no-op or
   may cause issues depending on the static binary version. Decide
   empirically on the first emulator run.
2. **Should `RuntimeService` be its own process
   (`android:process=":runtime"`)?** The current design keeps it in
   the main process for simplicity (the activity binds it directly,
   and a separate process would need AIDL). Revisit if a
   long-running WebSocket from the chat screen ever competes with
   the orchestrator for resources.
3. **Notification content** — show last `HealthState`? last 5
   lines of proot stderr? a static "Seed runtime running"? v0.1
   picks "Seed runtime running" (a static string); richer
   notifications are a Phase 10 polish.

---

## 6. Verification (end-to-end, on emulator)

The bar for "Phase 8/9 done":

1. `make run` boots the emulator, installs the new APK, launches it.
2. Logcat shows, in order: `BootController: state → Ready` →
   `MainActivity: startForegroundService` → `RuntimeService: onCreate` →
   `ProotRunner: spawn proot -r /data/data/com.seed.app/files/linux/rootfs /bin/sh -c ...` →
   `seed_backend: Uvicorn running on http://127.0.0.1:7777` →
   `HealthMonitor: Healthy(flask=up)`.
3. `adb shell ps -A | grep proot` shows the proot process.
4. `adb shell ss -tln` (or `netstat`) shows `127.0.0.1:7777` and
   `127.0.0.1:7778` LISTENing.
5. The Shell screen's `ls` returns a real directory listing
   (`bin dev etc home lib ...`).
6. The App screen's WebView shows the Flask app.
7. `adb shell input keyevent KEYCODE_HOME` (background the app)
   → the proot process is still alive 30 seconds later.
8. `adb shell am force-stop com.seed.app` → the proot process
   exits within 5 seconds (foreground service is torn down).
9. All 103 existing Android unit tests still pass; the new
   `ProotRunnerTest` + `HealthMonitorTest` bring the total to
   ~120.
10. The 102 existing backend tests still pass (no backend code
    changed).

---

## 7. References

- **Architecture overview:** `docs/plans/2026-06-30-seed-app-design.md` §3 (file layout) and §6 (process model).
- **Original task-level plan:** `docs/plans/2026-06-30-seed-v0.1-bootstrap.md` §Phase 7, §Phase 8, §Phase 9.
- **Extraction code spec:** `docs/plans/2026-07-03-seed-phase7-runtime-extraction.md` (the 7.3/7.4/7.5 tasks it describes are all done; the 7.2 "Run the build" task is the first thing this work plan executes).
- **Current TODO state:** `TODO.md` line 26 marks Phase 7 ✅ done, line 219 marks Phase 8 ⬜ not started.
