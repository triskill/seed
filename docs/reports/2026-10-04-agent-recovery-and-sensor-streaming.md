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

Next, after the separate recovery commit. Approved: 30Hz default, cap60Hz, latest
sample coalescing and automatic lifecycle/document cleanup with Sensors consent.
