# Runtime Shutdown Recovery Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Prevent replacement from overlapping an old runtime and establish reliable Android process ownership before accepting wedged-runtime restart.

**Architecture:** Serialize explicit restart in the supervisor worker, retain the old handle until a bounded termination check succeeds, and fail closed on timeout. This first gate uses the existing handle liveness contract; Android Process liveness/termination reliability remains unproven. A subsequent owned-PID identity/signal implementation must replace that uncertainty without hidden APIs or name-based process killing.

**Tech Stack:** Kotlin coroutines, JVM virtual-time tests, Android API26+, PRoot.

## Task 1: Bounded replacement gate (current offline increment)

Files: `android/app/src/main/java/com/seed/app/runtime/{RuntimeSupervisor,ProotRunner}.kt`, `android/app/src/test/java/com/seed/app/runtime/RuntimeSupervisorTest.kt`.

1. Add failing tests: restart queues destruction off caller; stubborn handle prevents replacement and shows error; delayed exit starts replacement only afterward; repeated restart during pending shutdown coalesces; stop cancels pending replacement.
2. Run `cd android && ./gradlew :app:testDebugUnitTest --tests com.seed.app.runtime.RuntimeSupervisorTest` and verify failures.
3. Add a suspending bounded exit-wait contract on ProotHandle. Keep ownership during explicit shutdown. Queue immutable commands carrying generation/restart intent; do not await under the lifecycle lock. Retry after timeout must not silently discard a live old handle.
4. Run all Android JVM tests, lint and APK builds. Record the gate as partial recovery hardening, not reliable device shutdown.

Task 1 status: implemented; all four new tests failed against the old behavior,
then all 25 supervisor tests passed. Full verification passed 272 JVM tests,
debug lint and both APK builds. Review found no blocking defects in this scoped
increment. The ten-second deadline bounds `awaitExit`, not synchronous pipe
closure inside `destroy()`; Android liveness remains unproven. No device run:
the phone is disconnected. Changes remain uncommitted.

## Task 2: Reliable Android ownership and termination (not implemented here)

1. Investigate a bounded host-shell launch receipt/acknowledgment for PID ownership, with injected identity inspection and positive-PID signal delivery. Never infer PID from a process name or assume an isolated process group.
2. Validate PID/start-time/UID identity before signals; test missing receipt, inspection denial, PID reuse, ineffective TERM/KILL and bounded wait. Avoid closing potentially blocked pipes on the UI thread.
3. Await TERM grace then KILL completion; report uncertainty as failure and preserve ownership. Do not assert that killing the tracer also kills guests without evidence.
4. Apply ownership cleanup to superseded starts and service shutdown, not just explicit restart.

## Task 3: Device acceptance when the phone returns

Repeat `docs/reports/native-arm64-runtime-restart-acceptance.md`: SIGSTOP owned tracer, await error, manual Restart, confirm old tracer/descendants release and new readiness. Check generated app, browser habit data, terminal re-creation and unchanged settings. No phone acceptance is available while disconnected. Do not clear user data as a workaround.
