# Agent Recovery and Sensor Streaming Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** First restore failed agent roles safely in a separate commit, then implement responsive bounded sensor streaming.

**Architecture:** Role-level supervision owns stop/reap/replacement and verifies RPC readiness, without replaying interrupted user tasks or weakening read-only filtering. Sensor subscriptions use the existing origin-bound JSON bridge with bounded native sampling/output and document/lifecycle-owned cleanup.

**Tech Stack:** Python asyncio/PiRunner, Kotlin SensorManager/WebMessage bridge, browser Promise SDK, existing role prompt assets.

## Evidence and constraints

Device logs show middleman ToolCallBlocked for edit/write; its consumer exits while the underlying reader ends on violation. Production PiRunners are built with auto_restart=False. Orchestrator read-loop failure sets unavailable/_ready=false and has no supervised replacement. Checked Python/interpreter/Flask/backend files match the local bundled image; no observed runtime mutation. Same-UID PRoot filesystem isolation is not solved by this task or chmod. Preserve app, credentials, role sessions, configured model and task metadata; no storage clear. Exclude pre-existing seed_version.json.

## Task 1: Role recovery (commit first)

Test failures first with actual fake subprocesses emitting a tool violation and an EOF/crash, plus injected factories for deterministic budget/stop-race tests. Keep ToolCallBlocked fatal to the old generation: stop and reap it before replacement, preserve read_only_tools/cmd/env/session identifiers, fail outstanding RPCs, discard old generation events/buffers. Never ignore/remove the allowlist or replay the failed operation.

Orchestrator owns bounded recovery for real roles: 3 replacements per role per orchestrator lifetime with small backoff; publish restarting/unavailable/ready accurately, verify get_state with a deadline before releasing role/readiness. Recovery must serialize with cancellation fallback and shutdown, no overlap, canceled startup collected, no late recovery after stop. Worker failure terminalizes active task exactly once as interrupted/failed; do not automatically resume partial file edits. Middleman failure reports failure without replaying the user's prompt or obsolete dispatch fragments. Healthy worker can continue when middleman recovers.

Budget exhaustion fails closed and leaves manual full Runtime Restart / authenticated settings agent replacement available (new orchestrator resets budgets). Do not silently enable PiRunner transparent task replay/EOF restarts in production. Existing behavior of non-Pi test runners may stay nonrecovering absent injected factory.

Run backend suite, review/fix races and security boundaries, report exact coverage, commit only recovery files. Device fault injection must use isolated fake roles, not provoke real model file edits, and preserve user app/data. Do not claim filesystem sandboxing.

## Task 2: Streaming (after Task 1 commit)

Approved API: `await seed.android.subscribe({method:'sensor.subscribe',params:{type:1,rateHz:30}}, sample=>update(sample))` -> stream with `stop()`. Native registry also supports sensor.subscribe/unsubscribe via common JSON protocol. Default 30Hz, cap60Hz; positive numeric request rates bounded, hardware rates are hints. Sensors consent shared with list/read; Android restrictions still apply. Continuous/on-change sensors only, reject one-shot trigger sensors for streaming rather than pretending periodic output.

Register one persistent listener per subscription; no per-sample re-registration or consent dialogs. Sample at suitable native interval, push bounded latest-value updates (no growing queue or bursts of stale data), timestamps/accuracy/type. Scope to exact configured origin and top frame, document identity/generation and current page. No wildcard, reflection, direct iframe grant or backend-origin privilege. Explicit unsubscribe is idempotent and belongs to caller document. Bound total active subscriptions and IDs; subscriptions don't occupy single-operation slot forever or prevent other capability calls.

Native event wire separates acknowledgments/results from stream events. SDK correlates subscription ack/events/errors and owns listener cleanup, stop promise, terminal event and pagehide/cached-page restoration behavior. Stop at navigation, tab disposal, background/ON_STOP, permission revocation and errors; clean native listener on callback failure/timeout/cancel. No auto-resubscription on return unless user/app asks anew; streams terminate predictably, no stuck JS handles or stale old-generation events. Avoid per-sample evaluateJavascript; use JavaScriptReplyProxy bound to document.

Test first: SDK ack/event/stop correlation, early event ordering, bounded burst coalescing, rates, multiple stream limits, concurrent one-shot operations, cleanup on navigation/background/stop/revoke, stale callbacks and unsupported/permission-denied sensor types. Update both agent prompts/schema/docs. Full JVM/lint/APK/SDK/backend checks and connected suite; isolated sensor fixture for real stream rates, no generated-app mutation. Device acceptance includes responsive 30Hz stream and stop/background cleanup; do not equate mocked rates with hardware guarantees. Commit streaming separately.
