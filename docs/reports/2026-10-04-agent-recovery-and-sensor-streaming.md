# Agent recovery and sensor streaming verification

## Recovery

Observed device failure: middleman ToolCallBlocked on edit/write ended its reader;
production PiRunners also defaulted to no automatic EOF restart. Python, libpython,
checked stdlib/Flask and backend source matched the bundled image; no observed
runtime corruption. This is not evidence of an enforceable filesystem sandbox.

Implemented Orchestrator-owned role recovery: three replacements per role/lifetime
with bounded backoff, old stop/reap before new spawn, preserved CLI/env/session/tool
filters, get_state deadline before ready, combined role readiness, no replay of
failed prompts or interrupted worker edits, terminal task status once, cancellation
fallback serialized with recovery, and shutdown ownership of starting replacements.
Exhaustion remains unavailable; full Runtime Restart or applying agent settings
creates a fresh orchestrator/budget. No allowlist was removed or broadened.

APK bundles only orchestrator.py/pi_runner.py updates; BackendPatchInstaller validates
assets and atomically replaces each module before backend launch. No rootfs image
replacement, app/auth/provider directory overwrite or data clear. Directory symlinks
are rejected; replacement is independently atomic per file, not transactional across
files. This does not make PRoot or shared Android UID permissions a security sandbox.

Verification:
- **307 backend tests passed**, one existing Starlette/httpx deprecation warning.
- **326 JVM tests**, debug lint, debug and instrumentation APK builds passed.
- Review found no important actionable issue; immediate post-probe EOF and all truly
  concurrent failure/cancel/shutdown combinations remain broader coverage work.
- Installed both APKs with -r; backend /health returned healthy. Installed module
  hashes match source.
- Moto G32 isolated guest Python subprocess probe: **middleman and worker recovery
  passed**, old runner cleanup complete, filters retained, failed prompts not replayed.
  A fake tool event executed no tool; no real model was asked to edit protected files.
  User's running roles were not fault-injected. Temporary guest probe removed.
- Settings/auth file and generated-app file hashes (excluding Python bytecode) matched
  before/after install/probe. Browser-local contents were not inspected or reset.
- Previous 35-method instrumentation suite not rerun for this recovery-only step.
  The native recovery probe is separate from Compose/stream/camera/location acceptance.

Local logs: /tmp/seed-recovery-full-tests.log, /tmp/seed-recovery-android-verify.log,
/tmp/seed-recovery-device/probe.log (private directory); no credential values printed.

## Sensor streaming

Implemented after recovery commit f48957d: generic SDK subscribe/stop/closed,
30Hz default / 60Hz cap / four subscriptions, persistent continuous/on-change
sensor listeners, document-owned IDs, remembered Sensors consent and live-reading
consent text. Stops on navigation, disposal, backgrounding, revocation and errors;
no automatic resume. Control calls bypass ordinary busy slots.

End-to-end ACKs follow callback completion (including asynchronous callbacks): one
unacknowledged sample plus one latest pending sample, exact sequence validation,
10-second timeout and listener cleanup. Callback failure stops the stream.
Review found no additional important blockers. Parent regressions caught timer
rounding that could halve delivery rate and cancellation leaving a live document's
Promise pending. Both were verified failing then passing. Native test return types
were corrected after JUnit rejected two inferred non-void test methods.

Verification:
- **333 JVM / 16 SDK / 308 backend tests passed**; existing backend deprecation warning.
- Debug lint, application APK and instrumentation APK builds passed.
- APKs installed with -r, no data clear. Final Moto G32 suite: **44/44 passed**,
  141.59 seconds; includes earlier consent, location and rotation regressions.
- Real accelerometer → native host → WebView bridge → SDK → ACK → stop:
  **38 samples in 1500ms** in final suite (37 in isolated run). Requested 30Hz,
  measured approximately 25Hz; hardware/renderer rates are not guaranteed.
- Native stream tests cover multiple samples, four-stream limit, revocation,
  callback failure, background/close, Allow once/canceled approval and ACK timeout.
- Settings/auth hashes (2 files) and generated-app hashes (28 files, excluding
  bytecode) match the pre-recovery baseline. Browser-local contents not inspected
  or reset. No photograph taken or live location coordinates logged.
- Production backend healthy after startup; both installed prompt hashes match
  source. Temporary ADB health forward removed.

Manual real-camera, Android location-dialog/live-GPS, process-restart consent and
user-generated-page rotation acceptance remain separate from instrumentation.
Shared-UID/PRoot filesystem isolation remains unresolved.
Logs: /tmp/seed-stream-final-build.log, /tmp/seed-stream-backend-tests.log,
/tmp/seed-stream-final-all-device-tests.log, /tmp/seed-stream-real-e2e.log.
