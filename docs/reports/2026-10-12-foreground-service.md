# Foreground-service Stop and timeout review — 2026-10-12

## Status: implementation deferred; release blocker remains

This pass reviewed the shutdown design but **did not implement** API 35 timeout
handling or a notification Stop action. No Kotlin, UI, manifest, notification
strings, process signals, runtime assets or constants were changed. The parent
explicitly deferred lifecycle implementation after the provider pause rather than
ship an unreviewed partial fix. This report is not evidence of runtime acceptance.

Reviewed: `RuntimeService`, `RuntimeBinder`, `RuntimeSupervisor`,
`RuntimeMaintenanceGate`, `RuntimeRestoreCoordinator`, `OwnedRuntimeProcess` and
its factory, `ProotRunner`, `SeedTerminalManager`, `MainActivity`,
`StartRuntimeScreen`, and the permissions/shutdown audit and Restore integration
and final-review reports.

## Current behavior and concrete gaps

- `RuntimeService` has no `onTimeout(startId, fgsType)` override and no notification
  Stop action. The service currently returns `START_STICKY`.
- Binder Stop calls `stopSelf()` directly. A bound service need not be destroyed
  immediately by `stopSelf()`; current owned cleanup is in `onDestroy()`.
- Destruction requests supervisor/terminal cleanup and cancels service scope. It
  does not establish whole-runtime confirmed exit or a retained STOPPING state.
- Restore can resume writers, factory launch admission, terminal admission and a
  new supervisor after its transaction. A timeout or Stop added independently
  could therefore race Restore and relaunch work after the stop request.
- Factory ownership is already cached across same-process service recreation;
  terminal ownership is retained too. This is not ownership recovery after app
  process death. Independent cleanup must preserve these owners on uncertainty.
- Factory `freezeAndStopConfirmed()` holds a monitor also used by the full launch
  handshake. It may wait behind the handshake before its exit timeout begins.
  A coroutine timeout around blocking monitor acquisition or `Process.waitFor`
  is not a hard overall deadline.
- Maintenance exclusion waits for admitted writers and serializes Restore.
  Freezing new writers alone neither ends an admitted writer nor proves guest
  exit. Synchronous Restore filesystem work also cannot be assumed to stop at
  a coroutine cancellation boundary.
- `MainActivity` currently displays Restore-only recovery when maintenance is
  active. A sticky shutdown freeze without UI integration would leave users
  unable to start normally without Restore. That is not an acceptable Stop UX.

## Agreed design requirements for the next implementation

The parent approved the following behavior, but it remains **unimplemented**:

1. Add a process-retained shutdown state and generation, independent of any one
   service instance. Distinguish stopping, unconfirmed cleanup and confirmed stop.
   Keep actual ownership references until exit is proved; do not equate signal
   dispatch, coroutine cancellation or notification removal with runtime exit.
2. On Main, both notification Stop and API 35 timeout immediately freeze writer
   admission, disconnect/freeze terminal views (including PID-0 lazy sessions),
   and reject startup, bind-triggered launch, queued restart/retry and Restore
   resume for the stopped generation. Restore resume must serialize its checks
   and admission changes with Stop; a volatile flag checked before dispatching
   a later Main unfreeze is insufficient.
3. Transfer cleanup to an independent retained owner before cancelling service
   work. Release foreground status and call `stopSelf()` promptly, without
   awaiting guest exit or three sequential cleanup timeouts. Bindings can keep
   the service object alive; safety must not depend on `onDestroy()` arriving.
   Stopped start requests must not request automatic sticky restart.
4. Perform owned cleanup off Main under one overall confirmation deadline,
   including waiting for existing writer/Restore work and launch handshakes.
   Review blocking factory operations explicitly: a timeout wrapper alone cannot
   bound them. A five-second confirmation attempt was discussed, **not adopted
   as a production constant**. Android lifecycle release must be independent of
   this attempt's duration. Pending admitted work must remain owned even if the
   attempt ends; do not abandon it on deadline expiry.
5. Use only existing verified identity/readiness QUIT/CONTINUE cleanup. No guessed
   PID signals, tracer-only TERM/KILL fallback or success inferred from missing
   inspection permission. Retry busy workers/uncertain confirmation only through
   retained ownership; keep all mutation and launch admission closed meanwhile.
6. Coordinate with Restore's existing exclusion. Stop must prevent a pre-stop
   Restore from reopening admission. Do not introduce a second filesystem
   transaction owner or cancel a transaction and assume rollback is complete.
   Only confirmed backend, failed-launch factory and terminal exit can authorize
   `markRuntimeStopped()`; existing writers/transactions must also have settled
   before reopening normal operation.
7. Expose explicit Start through the existing Retry path after Stop. While exit
   is unconfirmed, explicit Start may retry confirmation only. After confirmation
   it may deliberately reopen admission and construct a fresh supervisor.
   Bind/start callbacks and a later foreground app entry must not auto-resume;
   show Start instead. Requests queued before Stop must not count as new consent.
   Do not require destructive Restore or force-stop to recover from normal Stop.
8. Wire a private explicit notification service action with an immutable pending
   intent, and route binder Stop through the same controller. Adjust startup UI
   to show stopping/unconfirmed/stopped truthfully rather than a healthy backend
   or a Restore-only screen. Recreate service/binder observation without losing
   the retained shutdown generation or owners.

Expected edit scope: a small testable runtime shutdown controller, service and
binder integration, maintenance/Restore admission coordination, minimal
MainActivity/startup UI integration, notification strings and related tests.
Independent security/ownership review is required before final build acceptance.
No change to native signal policy or broad isolation architecture is intended.

## Required verification — not performed in this pass

Write and run behavioral JVM tests for:

- Stop/timeout idempotence; lifecycle release without waiting for a blocked
  handshake, process exit or Restore transaction.
- Queued startup/restart/retry and bind admission rejected after Stop; explicit
  post-stop Start resumes only after full confirmation.
- Stop during Restore stopping, filesystem work, and resume scheduling; no stale
  unfreeze, automatic relaunch or false stopped publication.
- Admitted writer exclusion and deadline expiry; independent ownership retained
  after service scope cancellation and same-process service recreation.
- Failed-launch/no-handle ownership; inspection denial, PID reuse, busy signal
  workers, partial backend/terminal success and repeated confirmation attempts.
- One overall cleanup budget (not a fresh timeout per handle/subsystem), including
  blocking operations; no claimed confirmation after the deadline.

Extend instrumentation for PID-0 view detachment, active terminal/backend guest
cleanup and service recreation. On an API 35+ device/emulator, exercise actual
`dataSync` timeout behavior and notification Stop while bound/unbound and during
Restore. Verify foreground lifecycle deadline compliance, no automatic restart,
truthful UI, explicit Start recovery, and preservation of user files. Tests must
observe guest ancestry/exit rather than only disappearing notifications or
tracer handles. Android's system task-manager Stop and app-process death have
separate lifecycle semantics; a service callback cannot be claimed to cover them.

**No new tests were run by this worker.** Release-engineering reported its unrelated
current baseline build successful: 382 JVM tests per variant, both lint variants,
both app APK variants and the AndroidTest APK compilation. That baseline does
not validate this deferred design; see release-engineering's report for its
commands, logs and final artifact results. Existing Restore/device results are
historical evidence for existing ownership paths, not timeout/Stop acceptance.

## Separate foreground-service policy decision

The manifest remains `dataSync`; it was not relabeled `specialUse`. Whether an
indefinitely running local interactive runtime qualifies for its declared type
and Play Console use case remains an owner/policy decision. Engineering timeout
handling alone does not establish eligibility. Confirm the product's runtime
lifetime, allowed service type and Play declaration before release. No policy
approval or Google Play readiness is claimed by this report.
