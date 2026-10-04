# Settings runtime restore — foundation report (incomplete feature)

## Delivered

- `RuntimeRestoreTransaction.kt`: an internal, **unwired** filesystem transaction. No existing runtime lifecycle behavior has changed.
- Same-filesystem staging under `linux/.restore-stage`; rename replacement with durable EXTRACTING/PREPARED/ROLLING_BACK/COMMITTED journal and file/directory synchronization.
- Automatic precommit rollback and postcommit removal of `.restore-old` and staging artifacts. The old tree is temporary transaction safety state, **not a permanent backup**, recovery feature or user snapshot.
- Full generated-app content copying, including hidden `.git`, DB/WAL files, uploads, executable files and symlinks copied without following their targets. Existing regular backend config is copied. Unsupported app entry types fail closed.
- Complete outside-rootfs Pi storage and Android settings/consent/browser storage are untouched; boundary tests use isolated fixture files, not real app stores.
- Legacy guest Pi data fails closed when nonempty rather than silently migrating/deleting it. No arbitrary rootfs-file backup.
- No-follow ancestor validation, bounded journal parsing, free-space reserve, cancellable preservation and extraction callback, interruption checkpoint/recovery tests.
- TODO updates record user manual remembered-consent persistence/revocation, rotation and location access-control acceptance; coordinate precision remains pending. Future optional paid server backup/sharing ideas are linked, not implemented or promised.

## Follow-up: backend confirmed-stop APIs (feature remains incomplete)

- `OwnedRuntimeProcessFactory.freezeAndStopConfirmed(timeoutMs)` is serialized with the full start handshake. It freezes future launches, requests safe owned cleanup (or raw unacknowledged-wrapper cleanup), waits for Java direct-child exit, and retains ownership on timeout/failure. `resumeLaunches()` refuses outstanding ownership. This blocking API is for an IO thread, never the main thread.
- `RuntimeSupervisor.stop()` now retains its installed handle. New suspending `quiesce(timeoutMs)` cancels/joins the command worker and awaits installed/late-rejected handles. A cancellation-resistant startup cannot escape the join; late handles that remain alive are retained for retry.
- Four new JVM regressions cover failed-launch freeze/exit, installed-handle timeout/retry, joining pending startup, and late cancellation-resistant launch cleanup. The last test produced a behavioral red (28 supervisor tests, one failure) before adding retained late handles.
- These APIs are **not** a production Restore stop proof until the service calls both under shared writer/launch exclusion. Terminal ownership, maintenance gate, startup recovery and UI remain unfinished. No unsafe Restore operation has been exposed.
- Follow-up verification: **348 JVM tests, zero failures/errors**; lint **0 errors, 38 warnings**; debug and instrumentation APK build tasks succeed. Log `/tmp/seed-restore-stop-verification.log`.
- Python combined run while Gradle ran: **309 passed, 1 failed** (`test_chat_ws_forwards_user_message_to_middle_man`, fake subprocess receipt timeout). Sequential full rerun: **310 passed**, one existing deprecation warning; `/tmp/seed-restore-stop-python-rerun.log`. No backend files changed, so the first failure has not been claimed fixed.
- `git diff --check` succeeds. No ADB/install/commits performed; preexisting asset version and parent-owned future/ADR/TODO edits untouched.

## Blocking unfinished integration — do not expose Restore yet

The requested Settings feature is **not complete**. No Settings confirmation/progress/retry UI, startup-failure entry, terminal receipt wrapper, process-wide lifecycle gate, startup recovery hook or real staged APK extraction/version adapter has been implemented here. Backend outstanding-launch stop APIs now exist as described above, but are not wired to a maintenance operation.

Current Termux `finishIfRunning()` sends SIGKILL directly to the tracer. Its waiter posts native waitpid completion to a main-thread handler. It cannot prove guest cleanup, and asynchronous waiting must not block that handler. PID zero means uninitialized (Termux `isRunning` still reports true); terminal initialization must first be excluded before treating that state as stopped. Verified terminal identity/expected executable/caught-QUIT readiness, QUIT+CONT cleanup and native waitpid completion are required, with retained ownership for failed/retired sessions. Unknown or previously discarded tracer ownership must fail closed.

Supervisor `stop()` still does not await exit (but now retains ownership); `quiesce()` must be combined with factory `freezeAndStopConfirmed()` before any live-tree mutation. All startup extraction, launch/retry/restart, terminal attachment/initialization, DNS callbacks, backend/prompt installers and recovery need application-wide writer/launch exclusion.

`confirmedStopped` is an injected transaction prerequisite used by fixtures, **not an implemented production stop proof**. There are deliberately no production call sites, so the half-integrated feature cannot accidentally run on user data. Parent has been explicitly asked to take/delegate this blocking ownership/lifecycle integration rather than relaxing the gate.

## Verification actually executed

From `android/`:

```
./gradlew :app:testDebugUnitTest --tests '*RuntimeRestoreTransactionTest' --console=plain
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --console=plain
```

- Initial focused red: compilation fails because transaction class is missing.
- Behavioral journal-publication red: 10 tests, 1 failure, before implementing interrupted publication recovery.
- Final combined verification: **344 JVM tests, 0 failures/errors**, including **10 transaction tests**. Lint: **0 errors, 38 warnings** (warnings are not a clean lint result). Debug and instrumentation APK build tasks succeed. Existing instrumentation compiles; no new UI fixture instrumentation exists because no UI was implemented.
- Build log: `/tmp/seed-restore-verification.log`; initial red log: `/tmp/seed-restore-red.log`.

From repository root:

```
.venv/bin/python -m pytest backend/tests webapp/tests -q
git diff --check
```

- **310 Python tests passed**, 1 existing FastAPI/Starlette/httpx deprecation warning.
- `git diff --check` succeeds.

No ADB commands, installs, user-data changes or commits were performed. Preexisting `android/app/src/main/assets/linux/seed_version.json` was not edited. Parent-owned decision/future-ideas documents were not edited by this agent.

## Remaining filesystem validation/risk

- JVM interruption tests simulate process interruption at rename/commit checkpoints; they are not physical power-loss tests and do not validate Android filesystem/SELinux directory-fsync behavior.
- Strict validation rejects any symlink ancestor, including potentially trusted Android `/data/user/0` aliases; integration must select/validate a trusted real files-directory base without following user-controlled Linux descendants.
- Existing rootfs is required; absent/unrecoverable preservation paths fail closed.
- Archive extraction is an injected callback, not yet tied to the APK extractor. Full archive size admission/streaming disk checks, required fresh runtime layout validation and metadata/version publication are unfinished. Reserve checks cover staging admission and preservation, not extraction streaming.
- App contents and executable bits are retained; complete POSIX permissions/timestamps/xattrs/hardlink identity are not promised. No unrestricted same-UID adversary resistance is claimed; writer exclusion and approved ownership proof remain caller prerequisites.
- Recovery cannot be described as enforced before startup until its startup/launch hooks are implemented under the lifecycle gate.

Implementation plan: `../plans/2026-10-07-settings-runtime-restore.md`. Future product ideas only: `../future-product-ideas.md`.
