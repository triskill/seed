# Public beta audit — 2026-10-12

**Decision: NO-GO for Google Play public beta.** Local release preparation is
healthier, but publishing is blocked by policy/product decisions and unfinished
engineering. Public beta is assumed to mean **Open testing**. No Console access,
Google approval, license selection, signed-device acceptance or uploads occurred.
The overall release goal is not complete.

Start here: [short owner instructions](../release/google-play-human-tasks.md).
Policy terms: [independently checked official sources](../release/2026-10-12-policy-review.md).
Detailed [security review](2026-10-12-security-review.md),
[licensing gaps](2026-10-12-license-gap.md) and
[deferred shutdown design](2026-10-12-foreground-service.md) identify the remaining
release work. Shutdown was **reviewed, not implemented** in this batch.

## Project assessment

Seed is an Android Compose shell around PRoot/Alpine, Python/FastAPI, Pi AI agents
and a generated Flask app in a WebView. The APK is fixed, but same-UID agents can
modify the runtime and execute shell commands. This is an explicit existing
product decision, not an isolation guarantee. Recovery does not prevent disclosure
or recover missing data. Package `cz.trety.seed` is separate from `com.seed.app`.

Existing branch work already targets API 36, pairs one ARM64 rootfs/native ABI,
builds an unsigned AAB and checks packaged ELF alignment, including rebuilt Termux
JNI. It does not prove functional 16 KB compatibility or Google policy acceptance.
The native-helper issue below means executable downloads are a real code path,
not merely hypothetical behavior of a sufficiently creative user.

## Changes made in this audit

- Disabled Android automatic backup (`allowBackup=false`) and explicitly excluded
  all nine app-storage domains from legacy backup and modern cloud/device transfer.
  This prevents ordinary platform backup selection of plain Pi credentials, sessions
  and generated data. Added two XML contract tests; Android build/lint validate the
  resources. OEM/privileged transfer, prior backups and real-device behavior are
  not certified. The merged release manifest confirms all three backup attributes.
  **Trade-off: no automatic restore or phone-transfer promise.**
- Expanded CI from three Termux fixtures to all 48 release-tool fixtures.
- Corrected Android setup instructions and misleading credential-storage comments.
  New credentials are private Pi JSON, not Android Keystore-encrypted storage.
- Updated privacy/Data Safety drafts with backup controls and catalog/helper network
  endpoints. They remain drafts, not legal approvals or submission answers.
- Added the linked owner checklist, current policy evidence and engineering reports.

No SDK, version, runtime timeout, service type or native-tool pins were changed.
No rootfs regeneration or agent restrictions were introduced. The pre-existing
`seed_version.json` build-ID edit is preserved. No secrets were requested or printed.

## Blocking work, in order

| Priority | Issue and concrete next step | Completion evidence |
| --- | --- | --- |
| P0 | **Executable-code policy / product scope.** Current Pi downloads missing rg/fd native tools from GitHub; this rootfs has neither. Unrestricted shell/package install and backend mutation are broader issues. Owner must choose a substantiated eligible design, enforceable restricted Play variant, or another distribution route. Do not silently undo accepted agent freedom. | Documented decision, actual download/mutation-path review and implemented boundaries where required; support guidance is not a substitute for final review. |
| P0 | **AI safety and in-app reporting.** Select supported provider safeguards, report receiver/retention/review owner. Then implement report UI, real HTTPS delivery with explicit user review of minimal data, error handling and moderation process. Never upload credentials/full logs by default or fake success with a local-only flag. | End-to-end report delivery/review tests and restricted-content handling evidence. |
| P0 | **Foreground runtime lifecycle.** dataSync cannot simply justify permanent runtime hosting. Decide genuine supported use case/type. Implement bounded onTimeout and user Stop coordinated with Restore, retained process ownership and explicit Start after confirmed cleanup. No ad-hoc stopSelf-only fix or type rename. | Race/unit tests and API35/36 device tests proving timely stop, no orphan/relaunch and safe restart; Console declaration/video. |
| P0 | **Privacy and disclosures.** Owner supplies identity/contact, provider/catalog terms, retention/deletion and public URL. Engineering adds in-app policy access and any required prominent disclosure/consent before collection. Trace release traffic including Pi/telemetry and generated-code flows. | Approved public policy, in-app UI, tested data flows, accurate Data Safety answers. |
| P0 | **Licensing.** Exact GPL version/only-or-later unresolved; distribute all required third-party notices and corresponding source for actual binaries/rootfs. Metadata inventories alone do not satisfy obligations. | Approved license, notices/source package tied to release inputs and artifact. |
| P0 | **Signed release / 16 KB acceptance.** Owner chooses key custody; build signed target36 artifact. Test Play-delivered ARM64 app on Android15/16 and 16KB pages, including generated app, providers, PTY, permissions, stop/restart, updates and Restore. | Signed artifact fingerprint, device/page-size details and acceptance results; clean pre-launch report. |
| P0 | **Account / track access.** Verify account and testing eligibility, then complete required closed test before Open testing. | Console verification/access; qualifying testers and production-access approval where required. |

Model-only `pi update --models` in bundled Pi 0.84.2 refreshes JSON from
`pi.dev/api/models/providers/`; it was **not** found to download native executables.
Do not confuse it with Pi's separate rg/fd helper downloader. Bundling these helpers
could remove that fallback but would not solve unrestricted shell policy by itself.
Flask disables the interactive debugger explicitly; the reloader remains prototype
behavior. Do not report an enabled Werkzeug console without evidence.

## Verified locally

- `make verify-python verify-python-static`: **310 tests passed**, one dependency
  deprecation warning; scoped Ruff and mypy passed (not full-project static analysis).
- `make verify-play-tools`: **48 passed**; both existing native Bash suites passed.
- Android debug and release JVM: **382 each**, no failures/errors/skips.
- Debug/release lint: **pass**, **42 / 44 warnings**, not warning-free.
- Debug APK, unsigned release APK and AndroidTest APK built. Instrumentation was
  compiled, **not executed**. No connected device was changed.
- Current 17 backend modules compared read-only with packaged runtime: only
  orchestrator differs, and it is already delivered by APK patch assets.
- Fresh `make release-check` and the unsigned release APK inspection **passed**.
  The AAB remains **unsigned**. See [release engineering results](2026-10-12-release-engineering.md)
  for current fingerprints. Previous `artifact-measurements.json`
  is historical, not measurements of this changed build. Device-specific delivery
  size and signed/Play-delivered behavior were not remeasured by this audit.
- `git diff --check` passed. CI coverage changed locally; no remote CI run was made.

Logs: `/tmp/seed-beta-python-checks.log`, `/tmp/seed-android-baseline-20261012.log`;
release-tool/build logs are listed in the engineering report. Backup guidance:
[Android Auto Backup](https://developer.android.com/identity/data/autobackup),
fetched directly 2026-10-12. A local artifact pass is not publication approval.

## Immediate owner response needed

1. Keep unrestricted execution and resolve its eligibility, approve a restricted
   Play variant, or choose another distribution route?
2. What Play account type/date and testing access do you have?
3. What legal identity, privacy/support URL/contact, exact GPL choice and report
   service/reviewer will be used?

These answers unblock the largest dependent work; the checklist gives the rest.
