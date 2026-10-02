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
the phone is disconnected. This gate was committed as `1906bc8`.

## Task 2: Android ownership and PRoot-specific termination (implemented; device pending)

1. Investigate a bounded host-shell launch receipt/acknowledgment for PID ownership, with injected identity inspection and positive-PID signal delivery. Never infer PID from a process name or assume an isolated process group.
2. Validate PID/start-time/UID identity before signals; test missing receipt, inspection denial, PID reuse, ineffective TERM/KILL and bounded wait. Avoid closing potentially blocked pipes on the UI thread.
3. Source validation supersedes generic TERM/KILL: pinned PRoot ignores TERM;
   send verified tracer QUIT then CONT and await cleanup. Tracer-only KILL is
   not full guest cleanup (no EXITKILL). Keep Java exit observation initially;
   reliable liveness failure has not been established. See
   `docs/reports/2026-10-02-runtime-shutdown-design-validation.md`.
4. Apply ownership cleanup to superseded starts and service shutdown, not just explicit restart.

Task 2 status: factory/receipt handshake, verified positive-PID QUIT/CONT,
expected-executable/caught-handler readiness gate, retained launch ownership and
bounded independent signal/pipe cleanup implemented. All **293 JVM tests**, lint
and both APK builds passed; review findings corrected. After the phone returned,
the isolated Android ownership smoke passed and all 11 connected tests passed. Implementation details and remaining
non-atomic identity/device caveats are recorded in
`docs/plans/2026-10-02-owned-runtime-process-design.md`.

## Task 3: Device acceptance when the phone returns

Repeated the real device scenario after the phone returned: SIGSTOP owned
tracer, await error, tap Restart, confirm old tracer/captured descendants release
and new readiness. Both attempts passed (~13.2/~16.7 seconds), including a
fresh terminal session after closing an active one. Settings/generated-app file
checksums matched. No force-stop/data-clear workaround was needed. The user subsequently confirmed browser-local habit data
is still present (manual persistence acceptance, not a store hash). See the acceptance report; x86_64 and
broader crash-race coverage remain separate.
