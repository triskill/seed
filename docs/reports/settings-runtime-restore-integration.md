# Settings runtime Restore integration

This supersedes the **unwired** status in the earlier foundation report. Production feature wiring is implemented; device acceptance and the terminal-owner shared-gate review remain separate.

## Implemented

- Native Settings and startup-failure Restore confirmation/status/error/retry; no HTTP/Python dependency. Maintenance-only foreground service binding skips backend startup.
- Service-owned operation survives Activity recreation. Duplicate requests are ignored, not queued for later replay.
- Application-wide gate excludes Boot extraction and synchronous launches/root writers. Synchronous admission never blocks Main behind IO receipt handshakes. Failed Restore leaves writers frozen until explicit successful retry.
- Service runs terminal Main-thread freeze, bounded supervisor quiesce, concrete cached factory freeze/confirmed exit, and suspending terminal confirmed exit on IO. Any failed/unknown proof prevents filesystem transaction. The terminal manager is retained with application context across same-process service recreation, like the cached process factory.
- Fresh packaged APK extraction under same-filesystem staging; require rootfs.tar and seed_version.json, archive-size/free-space admission, streaming free-space reserve, core Python/Node/backend validation. Native libraries are not extracted or overwritten.
- Full generated-app/config preservation with transactional precommit rollback, automatic committed cleanup and no permanent old-runtime backup. Host Pi/Android data remain outside the mutation boundary. Nonempty legacy guest Pi fails closed.
- Committed recovery publishes trusted APK version metadata **before** removing its journal; failed metadata publication retains the journal for startup retry. Boot recovers before reading readiness markers; service launch/installers/DNS recover before writing.
- Supervisor cancellation-resistant command-loop join now times out, retains ownership and does not authorize mutation. A fresh supervisor is constructed after successful Restore.
- Trusted Android parent-directory aliases are canonicalized, but the Linux directory/rootfs descendants remain subject to no-follow validation.

## Verification actually run

From android/:

```
./gradlew testDebugUnitTest testReleaseUnitTest lintDebug assembleDebug assembleDebugAndroidTest --console=plain
```

Latest run: **BUILD SUCCESSFUL**, 3m03s; **362 debug JVM tests and 362 release JVM tests, zero failures/errors**. Lint: **0 errors, 40 warnings**, not warning-free. App and instrumentation APKs build. Log: `/tmp/seed-restore-integration-final.log`. `git diff --check` succeeds.

New tests cover failed stop/no mutation, duplicate admission, committed metadata recovery, metadata-publication retry, missing assets, retained writer freeze and admitted-writer exclusion. New instrumentation compiles: `RuntimeRestoreSettingsTest` (native confirmation/cancel/progress/retry) and `PackagedRuntimeRestoreTest` (isolated cache-directory restore using APK assets). Neither instrumentation class was executed by this agent. An intermediate full test command hit its 180s tool timeout; a subsequent 300s run succeeded.

The initial gate test compilation failed before the gate implementation (concurrent terminal test code also lacked its implementation then). Not every integration edit has an independently observed behavioral red result; this report does not claim otherwise.

No ADB/install/user-data changes, seed_version asset edits or commits by this agent. Parent owns device acceptance, baseline hashes and final overall review. Existing backend/Python code was not changed or retested by this integration agent.

## Outstanding review / limitations

- Terminal-owner must integrate the shared gate on creation/attachment/root writers, without nested `withWriter` calls. The latest terminal file inspected did not yet include those hooks. Service freeze/stop is wired, but Boot/terminal cross-entry serialization must not be called fully verified until that owner finishes.
- Verify real device directory-fsync/atomic-rename behavior, interrupted journal recovery, active backend+PTY cleanup, failed-stop retry, broken-Python entry and app/auth/settings/browser-data preservation. JVM fixtures are not physical power-loss tests.
- The existing rootfs and preservation paths must be safe directories/regular files. Unrecoverable or symlinked paths fail closed; this does not restore already-lost data or leaked credentials.
- Preserve app contents/executable bits, not arbitrary POSIX xattrs/timestamps/hardlink identity. No sandbox against unrestricted same-UID guest execution is claimed.
- Restore stops tasks/shell commands and never submits/replays task prompts. It is not package-data reset, server backup or arbitrary-files snapshot.

Plan: `../plans/2026-10-07-settings-runtime-restore.md`. Future product ideas: `../future-product-ideas.md`.
