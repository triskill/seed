# Settings Runtime Restore Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Discard the broken embedded runtime while retaining the generated app and user state, without permanent backups or unverified process termination.

**Architecture:** Stage a fresh APK rootfs under the same `filesDir/linux` filesystem. A durable rename journal rolls back before commit and automatically cleans transaction artifacts after commit. A process-wide lifecycle gate must exclude every launch and rootfs writer; only verified backend and terminal exit permit mutation.

**Tech Stack:** Kotlin coroutines, Java NIO/filesystem synchronization, Commons Compress TAR extraction, Android foreground service, Termux PTY callbacks, Compose Settings.

---

## Approved preservation boundary

- Preserve the complete `rootfs/home/seed/app`, including hidden `.git`, DB/WAL files and uploads.
- Preserve an existing regular `rootfs/home/seed/backend/config.json`.
- Leave the complete outside-rootfs `linux/pi-agent`, DataStore, preferences/consent and WebView browser storage untouched.
- Preserve legacy guest Pi safely or fail closed; do not silently discard it.
- No permanent broken-runtime backup. `.restore-old`, `.restore-stage` and journal files are only transaction artifacts and are automatically removed after successful commit/recovery.
- Warn explicitly that arbitrary files elsewhere in rootfs are discarded. Never imply all-files snapshots.
- Existing Restart remains distinct; future optional paid server backup/sharing ideas are not this restore's mechanism or a product commitment. See `../future-product-ideas.md`.

## Task 1: transaction foundation (implemented and wired)

Files: `android/app/src/main/java/com/seed/app/runtime/RuntimeRestoreTransaction.kt`, matching JVM test.

1. Write fixture tests for generated-app/config preservation, untouched outside storage, failed stop/extraction, symlink ancestors, free-space/cancellation, interruption at every rename/commit checkpoint.
2. Run focused JVM tests before implementation: missing transaction class produces the expected unresolved-reference red result.
3. Implement EXTRACTING/PREPARED/ROLLING_BACK/COMMITTED journal, same-filesystem atomic renames, bounded journal parsing, no-follow recursive copying/removal, copied symlinks rather than dereferencing, cancellation checks and free-space reserve.
4. Run tests; add a behavioral red test for interrupted journal publication/recovery, then implement recovery of temporary publication and repeated rollback cleanup.
5. Run all JVM tests, lint and both APK builds. Do not commit (parent instruction).

## Task 2: required process ownership and lifecycle integration (implemented; device acceptance pending)

Files: `OwnedRuntimeProcess.kt`, `AndroidOwnedRuntimeProcessFactory.kt`, `SeedTerminalManager.kt`, `SeedTerminalClient.kt`, `RuntimeSupervisor.kt`, `RuntimeService.kt`, `BootController.kt`, `MainActivity.kt`.

1. Add tests and a terminal-specific receipt wrapper that keeps the PTY PID constant through exec. Independently validate UID, start ticks, parent, expected native executable and caught QUIT handler before signaling QUIT then CONT.
2. Use Termux `onSessionFinished` only as the owned direct-child waitpid completion observation; never block the main thread waiting for its main-handler cleanup. PID zero is unstarted, not exited; block all later initialization before treating it as safe.
3. Keep every terminal launch/failed/retired session owned across service recreation until its exit is confirmed. Existing `finishIfRunning()` sends SIGKILL to the tracer and cannot establish guest cleanup; previously discarded ownership must fail closed.
4. **Implemented and JVM tested:** factory `freezeAndStopConfirmed()` serializes with the complete handshake, freezes launch admission, retains failed raw/owned launches until Java confirms exit, and leaves admission frozen on failure. `resumeLaunches()` refuses unconfirmed ownership. Supervisor `quiesce()` cancels and joins outstanding startup, retains installed handles after `stop()`, and tracks late rejected handles until confirmed exit. The service combines both stop proofs with terminal exit under the shared maintenance gate. Supervisor join now has a timeout and retains ownership on failure.
5. Serialize service startup/retry/restart, terminal creation/attachment/initialization, startup extraction, backend/prompt installers and DNS writes under one application-wide maintenance gate. Recovery must run under that gate before every launch/writer. Avoid a main-thread mutex deadlock around PTY callbacks.
6. Test denied ownership, readiness timeout, signal failures, reused PIDs, cancellation and late starts. No guessed PID kill, TERM/KILL of PRoot, force-stop or data clear.

## Task 3: extraction and native UI (implemented; device acceptance pending)

1. Reuse safe TAR extraction against `.restore-stage`, never the live tree. Confirm staged metadata/version publication cannot cause the next boot to re-extract over preserved app data. Include archive-size admission and space checks during extraction.
2. Validate the staged required runtime layout before rename; use fixture archives for traversal, symlink and interrupted extraction tests.
3. Native Settings confirmation/progress/error/retry works without backend health. Explicitly state the preservation boundary and discarded arbitrary rootfs files. Expose the native surface from startup failure without rewriting navigation.
4. Bind Restore only after Task 2's verified exit gate exists. An injected `confirmedStopped = { true }` is test plumbing, not production safety proof.
5. Compile fixture-based UI instrumentation; do not execute against actual user data. Parent performs explicit device acceptance separately.

## Integration status

Production call sites now run service-owned Restore using supervisor quiesce, concrete factory freeze/confirmed stop and terminal freeze/confirmed stop. Native Settings and startup failure expose confirmation/progress/error/retry without HTTP. Maintenance-only binding skips backend startup. The application gate serializes Boot extraction and rejects synchronous launch/root writers during maintenance, retaining writer freeze on failure. Committed-journal recovery republishes trusted APK metadata before journal cleanup. Extraction uses a fresh staging tree and validates Python/Node/backend layout.

Full debug/release JVM suites and debug APK/instrumentation compilation have passed locally. Packaged-asset isolated Restore and native UI instrumentation are added, not yet executed on a device. Parent owns device acceptance and terminal gate review. See `../reports/settings-runtime-restore-integration.md` for current limitations and verification evidence.
