# Owned Android Runtime Process Design

## Problem and evidence

Real-device restart acceptance failed with a stopped old PRoot tracer and a new
runtime generation. Commit `1906bc8` now retains the previous handle through a
bounded exit-wait gate, but both shutdown and liveness still use Java Process
APIs. Subsequent source validation found that PRoot ignores TERM and inspected
Android AOSP force-destroy need not send KILL; broken Java liveness itself has
not been established. See `docs/reports/2026-10-02-runtime-shutdown-design-validation.md`.
Synchronous pipe
closure can also block before signal delivery. No phone is currently connected.

## Recommended design

Keep existing JVM ProcessFactory defaults and command assertions. Add an
Android-specific owned launch/lifecycle adapter that RuntimeService explicitly
injects into ProotRunner. Separate pure identity/policy code from Android I/O.

A fixed `/system/bin/sh` wrapper publishes a receipt containing a random nonce,
PID and kernel start time, then waits for matching parent acknowledgment before
`exec "$@"`. Paths and argv are separately supplied positional arguments,
never interpolated shell commands. Allocate a fresh private directory under
`cacheDir/runtime-ownership`, outside replaceable rootfs and guest bindings.
The handshake has deadlines on both sides. A receipt alone never authorizes a
signal: validate nonce, regular file/size, PID, start time, app UID and direct
parent relationship while the wrapper is waiting. Missing/invalid ownership
fails startup rather than guessing. No stale receipt is recovered as ownership.

Record immutable PID/start-time identity before acknowledgment. Revalidate it
immediately before each positive-PID Android `Os.kill`. For the pinned PRoot,
request tracked-guest cleanup with SIGQUIT, then SIGCONT so a paused tracer can
run the pending handler, and await exit with a deadline. This kills guest work;
it is not graceful uvicorn shutdown. Never signal a changed identity; denied
inspection/ineffective signals are uncertainty, not successful shutdown.
Do not use tracer-only SIGKILL as evidence of full cleanup: the pinned build
has no PTRACE_O_EXITKILL.
No group kill unless an isolated process group has been explicitly established
and validated. Kernel check-to-signal PID-reuse races remain a limitation;
atomic pidfd ownership would be a separate native implementation if required.

Destroy must schedule idempotent cleanup independently of the service scope
and return promptly. Signal delivery must not wait on pipe closure. Cancel log
collectors/channels separately and bound the admission of daemon pipe-cleanup
workers so a stuck close cannot delay shutdown or create unlimited threads.
Retain Java direct-child `isAlive`/waitFor observation initially: source does
not establish that it is broken, and a second cached liveness state machine
would add needless complexity. Do not perform identity I/O under lifecycle locks. Inspection uncertainty retains ownership and blocks replacement,
including ordinary Retry; cancellation/superseded launches use the same cleanup.

## Alternatives

- Java Process/reflection: smaller change, but no reliable public Android PID
  API for this target and hidden-API restrictions; rejected.
- Native launcher/JNI: stronger owned child/waitpid or pidfd potential, but
  expands native builds/packaging. Keep as fallback if shell/proc verification
  cannot work on the supported phone.
- Name-based pkill or inferred process-group kills: rejected; can terminate
  unrelated sessions/processes and do not establish ownership.

## Tests and rollout

1. Pure tests for `/proc/stat` parsing (spaces/parentheses, field22, truncation),
   identity mismatch/reuse, QUIT/CONT deadlines and idempotence.
2. Injectable launcher tests for receipt nonce/UID/parent mismatch, timeout,
   cancellation, no acknowledgment, exec failure and bounded cleanup.
3. Runner/supervisor tests for uncertain identity blocking replacement and
   Retry, blocked pipes not blocking signals, superseded launches and teardown.
4. Full JVM/lint/debug and instrumentation APK builds while offline.
5. When reconnected: real Android receipt/signal test, stopped-tracer restart,
   descendant and port release, terminal re-creation and user-data checks.

Tracer exit alone does not prove descendants are gone. The actual Termux
`--kill-on-exit` behavior under forced tracer termination must be established
on device before claiming reliable restart acceptance. Do not clear app data
or force-stop the package as a substitute for passing the Restart action.

## Implementation and offline verification

Implemented as `OwnedRuntimeProcessFactory` plus a delegating `Process` wrapper:
existing ProcessFactory command tests and Java direct-child streams/reaping stay
unchanged. The Android adapter is injected by RuntimeService and retains launch
ownership across service recreation. Every spawned child, including failed and
superseded handshakes, remains gated until exit; Retry can re-request cleanup.

The receipt/ack handshake establishes origin, not handler readiness. Before QUIT,
the Android policy additionally verifies expected `/proc/<pid>/exe`, captured
identity and the `SigCgt` caught-QUIT bit. It waits up to five seconds for this
boundary; unknown readiness refuses signals and preserves the exit gate.
Signal workers are bounded and independent of service cancellation; separate
bounded pipe workers run after exit. Drains close their channels on cancellation
without closing readers on service IO workers. Transient worker/inspection
failures can be retried without concurrent duplicate cleanup.

Offline verification: **293 JVM tests**, debug lint, debug APK and instrumentation
APK builds passed. Tests include real Linux receipt/exec with literal argv,
identity/nonce/UID/parent/symlink rejection, failed-launch gating/retry, QUIT/CONT
ordering, cleanup readiness, admission retries, concurrent dispatch and blocked
pipe/backpressure cancellation. Review findings were corrected and re-reviewed.
The phone subsequently returned (Moto G32, Android13/API33); user-authorized
installation and device testing passed: isolated ownership smoke **1/1**, full
connected suite **11/11**, and two real stopped-PRoot Restart recoveries
(~13.2/~16.7 seconds). All captured old-generation processes disappeared;
an active terminal was closed and a new session printed its marker after the
second restart. Settings and generated-app file checksums matched. See
`docs/reports/native-arm64-runtime-restart-acceptance.md` for details and limits.

**Next:** x86_64 and broader crash/supervision coverage. Browser-local habit data
contents still require user confirmation; they were not inspected by these
checksums. `/proc`/signal checks remain non-atomic; this is not a sandbox against
hostile same-UID code or a pidfd guarantee.
