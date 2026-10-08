# Runtime permissions and foreground-service shutdown audit

## Scope and status

Task 4, shared `app-store-preparation` branch. No commit, NDK/source/tooling, GPL asset, runtime JSON, manifest or foreground-service behavior changes by this task. Scoped Gradle JVM tests ran with the native AAR rebuild excluded after parent authorization. No phone, ADB, secrets or runtime acceptance evidence.

**Targeted hardening implemented; scoped JVM tests green (20 tests).** Parent final verification passed 380 JVM tests per variant, both lint/build variants and actual artifact gates. See `play-release-engineering-batch.md`. Device runtime acceptance remains outstanding.

## Permission findings and changes

- `GuestDns.write`: a temporary file starts at 0600, but `setReadable(true, false)` made it 0644. Replacement now applies exact POSIX owner read/write (0600), rejects existing resolver symlinks and uses NOFOLLOW_LINKS regular-file classification.
- `RuntimeExtractor.extractRootfs`: final guest agent bind directory formerly used everyone-readable/writable/executable setters (0777). Final mode is now exact owner read/write/execute (0700), removing pre-existing group/other bits, including a TAR mode of 0777.
- `RuntimeFilePermissions`: POSIX attribute view with NOFOLLOW_LINKS, reject symlinks/mismatched types, fail closed when unavailable, replace permission set rather than merely adding owner bits.
- Directory deletion recovery now ends at exact 0700. An initial no-follow POSIX chmod experiment failed on owned mode-000 directories with `AccessDeniedException`. The reviewed solution first checks directory type without following links, then bootstraps owner read/write/search using `File` setters with ownerOnly=true, and finally applies the exact no-follow POSIX permission set. This retains mode-000 recovery without granting new group/other access.
- The bootstrap `File` setters follow paths: the no-follow type check and subsequent chmod are not atomic against concurrent same-UID substitution. Static symlinks are rejected before bootstrap. This accepted residual limitation matches the existing check/chmod boundary; it does not establish hostile same-UID isolation. Only caller-selected app-owned runtime directories use the helper; no chown or broad chmod policy was introduced.

Unchanged: general rootfs file classification, executable-mode mapping, musl/fake-UID and guest temporary-directory compatibility. These need functional checks before any broad chmod policy. Host modes do not isolate the same-UID guest runtime from app data. NOFOLLOW_LINKS protects the final component; it is not a complete ancestor/same-UID concurrent path-substitution defense.

## Test evidence and outstanding checks

Tests were written before production edits in `GuestDnsTest.kt` and `RuntimeExtractorTest.kt`: DNS 0644 to exact 0600; symlink rejection with unchanged target content/mode; TAR 0777 agent directory to exact 0700. Existing mode-000 extraction replacement integration test remains. `RuntimeFilePermissionsTest.kt` adds exact modes from initial 0777/000, static directory symlink rejection with unchanged target mode, file 0777 to 0600 and wrong-type rejection.

A standalone Java JVM regression harness executed the original setters before implementation. It printed `Old DNS mode: rw-r--r--; old recovery mode: rwxrwxrwx` and failed with `AssertionError: Expected DNS 0600 and directory 0700`. This is actual evidence for the old setter semantics, **not** a run of the Kotlin/JUnit integration tests.

Parent recorded actual integration RED in `/tmp/seed-play-permission-red.log`: 17 tests, 3 failures (DNS mode, resolver symlink rejection, agent-directory mode), before implementation resumed. Earlier exploration exposed the no-follow mode-000 limitation; its temporary production edits were reverted before this parent-run RED.

Authorized GREEN command from `android`: `./gradlew --no-daemon :app:testDebugUnitTest --tests '*GuestDnsTest' --tests '*RuntimeExtractorTest' --tests '*RuntimeFilePermissionsTest' -x :app:rebuildTermuxAar`. Actual output in `/tmp/seed-play-permission-green.log`: BUILD SUCCESSFUL. XML results: GuestDnsTest 4, RuntimeExtractorTest 13, RuntimeFilePermissionsTest 3; zero failures/errors/skips. Compilation emitted four nullable-parentFile warnings in GuestDnsTest (including existing test lines); no runtime test failures. Native rebuild was not invoked; parent-provided manual AAR was used.

Parent full verification is recorded in `play-release-engineering-batch.md`. Neither scoped nor full JVM GREEN is native/device acceptance.

## Shutdown ownership findings (audit only)

Reviewed `RuntimeService`, `RuntimeSupervisor`, `OwnedRuntimeProcess`, `AndroidOwnedRuntimeProcessFactory`, `SeedTerminalManager` and `RuntimeMaintenanceGate`.

- `RuntimeSupervisor.stop()` terminates admission/cancels its command job and requests `destroy()`; it does not await confirmed exit. `quiesce()` joins launch work and awaits handles, but factory confirmation is independently required for failed launches with no returned handle.
- `OwnedRuntimeProcess.destroy()` schedules independent, bounded-admission signal workers. They check identity and cleanup readiness before QUIT, recheck identity before CONTINUE, and do not use guessed PID TERM/KILL fallbacks. A request/dispatch is not confirmed exit.
- **Factory correction:** `androidOwnedRuntimeProcessFactory()` caches factories in static `AndroidRuntimeOwnership.factories`, keyed by receipt-root canonical path. Ownership is retained across service recreation in the same app process; it is NOT service-local. This preserves outstanding/failed backend launch ownership and launch admission safety. It does not establish persistence after app-process death.
- Terminal ownership is also retained via `RuntimeService.retainedTerminalManager`. Its independent scope remains available for asynchronous close/reaping. It freezes/disconnects views to prevent lazy PID-0 session creation and retains uncertain sessions rather than declaring them stopped.
- `RuntimeService.onDestroy()` closes terminal/binder, requests supervisor stop and cancels service scope. It has no timeout callback override and no whole-runtime confirmed shutdown transaction. Restore uses separate backend/factory/terminal waits nominally 10 seconds each; supervisor may wait on multiple handles. This cannot simply be copied into a bounded Android callback.
- Maintenance writer exclusion freezes admitted app writes, and exclusive maintenance waits for existing writers. **Freezing writers does not prove all guests are stopped.** Restore only marks runtime stopped after backend, factory and terminal confirmation.

## Required separate shutdown design

Do not add an ad-hoc `stopSelf()` or `onTimeout()` implementation while Restore may asynchronously resume/relaunch. A reviewed process-wide STOPPING state must immediately, on Main, freeze writers and terminal/view admission; reject startup, bind-triggered launch, restart and Restore resume. Transfer cleanup ownership to an independent retained scope, with a single overall IO cleanup deadline and confirmed QUIT/CONTINUE/exit handling. Timely Android `stopForeground`/`stopSelf` lifecycle completion must be bounded independently of potentially long cleanup; it must not wait sequentially for three 10-second jobs in a callback. On uncertainty retain ownership and frozen/no-mutation state; never infer success or kill guessed PIDs. Coordinate existing admitted writers and concurrent Restore before reopening anything.

Required race tests include shutdown during ownership handshake, PID-0 terminal attachment, queued restart/bind, Restore stopping/resuming, inspection denial/PID reuse, busy cleanup workers, and service recreation with outstanding backend and terminal ownership. Device-level deadline/foreground-notification and guest cleanup tests remain unperformed.

## Foreground-service policy gate

Manifest currently declares `dataSync` and its permission. Whether an indefinitely running local backend/interactive agent runtime fits this type is an owner policy decision, not established by technical ownership safety. Confirm applicable Android version timeout/start restrictions and Play Console foreground-service declaration/use-case evidence before release. No type switch, permission addition, timeout callback or service-stop shortcut is made in this batch.
