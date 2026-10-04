# Native runtime Restore: final local review

## Current implementation

Native Settings and startup-failure recovery expose confirmed Restore with progress,
failure and retry. It does not require a working Python HTTP backend. Restoring
stops work and the terminal, preserves the generated-app workspace/config and
outside-rootfs Pi/Android data, and replaces the runtime from packaged assets.
Other Linux files are explicitly outside preservation scope. There is no permanent
backup of the replaced runtime: staging, rollback tree and journal are temporary
transaction artifacts cleaned after successful completion.

Backend failed-launch ownership, bounded supervisor quiescence and terminal receipt
ownership use verified QUIT/CONT cleanup and confirmed exit. Rootfs mutation fails
closed on uncertain stop. Terminal creation/attachment now participate in the shared
writer gate without nesting its admission. Root-dependent navigation is blocked
while restoration is active/frozen; Shell rejected admission has a native fallback.

## Review fixes

- The durable journal records the exact extracted source version. Recovery after
  an APK update publishes that recorded version, not the newer caller version.
- Boot refuses destructive upgrade over existing app/config data and directs the
  user to preserving Restore instead.
- Maintenance service observation survives Activity recreation independently of
  boot readiness.
- Missing rootfs is recoverable using an empty temporary rollback anchor. This
  cannot reconstruct already-deleted generated-app data. Its regression failed
  before the fix and passes afterward.
- Release lint required an explicit SecurityException catch around location listener
  registration; the existing broad exception cleanup behavior is retained.

## Verified locally

Final command:

```
cd android && ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest --console=plain
```

BUILD SUCCESSFUL, 3m13s. **367 debug + 367 release JVM tests** pass, as do both
lint variants and both app build variants; instrumentation compiles. Lint success is not a claim of zero
warnings. Log: `/tmp/seed-restore-reviewed-final.log`.

## Device acceptance

Moto G32 reconnected; both APKs installed with -r. Full suite **52/52 passed**,
304.596 seconds, including packaged restore on isolated cache data, native UI
confirmation/progress/retry, PID-zero terminal admission, paused tracer QUIT/CONT,
verified sleeping guest ancestry disappearance, and fresh terminal recreation.
Production backend healthy after startup. Health forward removed.

Device testing caught Android's /data/user/0 parent alias: direct transaction
construction now canonicalizes only the trusted parent, retaining no-follow checks
on Linux/rootfs descendants. The failing packaged device test passes afterward.
The terminal fixture also needed renderer text-size initialization, a transcript
check without trimmed trailing whitespace, and guest PID receipt/kernel ancestry
instead of reliance on Android exposing /proc/.../children. These were fixture
issues; no relaxed shutdown proof or arbitrary PID signals were introduced.

Settings/auth hashes (2 files) and generated-app hashes (30 files, excluding
bytecode) match immediately before/after install/tests. Private snapshots/logs are
in `/tmp/seed-runtime-restore-acceptance`. Browser data was not inspected or cleared.
No production Restore or deliberate corruption of the real user workspace was
performed. Tests isolate packaged filesystem replacement from user data; this is
not physical power-loss acceptance or a claim of recovery from every possible
corruption. Actual user-triggered Settings Restore remains manual acceptance. Preexisting
`android/app/src/main/assets/linux/seed_version.json` remains uncommitted/untouched.

Earlier foundation/integration reports are historical milestones; this report
supersedes their incomplete wiring/shared-terminal-gate status, not their recorded
verification. Future server backups, sharing and possible paid services remain
ideas only in `docs/future-product-ideas.md`.
