# Play release engineering batch — execution results

2026-10-07, branch `app-store-preparation`. Plan:
`../plans/2026-10-07-play-release-engineering-batch.md`.

## Completed scope

1. **Termux JNI rebuilt from unchanged pinned source**, not ELF-header patches.
   Exact v0.118.3 commit, original AAR and source/license hashes are enforced.
   NDK r28c 28.2.13676358 builds arm64-v8a and x86_64 with genuine 16 KB load
   alignment. All eight original non-native AAR entries/classes remain byte-identical;
   source/notices are added. Original transitive JNI is excluded. APK/AAB contain
   only the ABI paired with the unchanged runtime metadata. Detailed pins and
   contracts: `../release/termux-native-provenance.md`.
2. **Runtime package metadata is reproducible** with `scripts/inventory-runtime.py`.
   One source descriptor, hash/consistency checks, selected-metadata budgets, atomic
   output and redacted failures. The 65 Alpine / 49 Python / 311 npm snapshot is
   byte-identical; rootfs was not changed. This is metadata evidence, not a complete
   legally cleared SBOM. See dependency inventory for command and scope.
3. **make release-check builds before inspecting**, including parallel Make runs.
   Build/audit errors propagate; stale/custom PLAY_BUNDLE paths are rejected.
   Manual PLAY_ARTIFACT does not bypass the fresh gate. Unsigned bundle build remains
   separate. The strict native/inventory gate now **passes**.
4. **Targeted permissions hardened:** DNS replacement 0600; agent bind and deletion
   recovery directories 0700, including mode-000 bootstrap. Static symlinks rejected.
   General guest TAR executable classification is unchanged; generated app/config
   permissions are not recursively rewritten. Private parent directories and same
   kernel UID remain the actual boundary, not new agent isolation.
   **FGS shutdown was audited, not implemented**: confirmed factory ownership is
   statically retained across same-process service recreation. Future bounded
   timeout/user-stop design must coordinate Restore, admission and independent
   cleanup; no unsafe stopSelf shortcut/type change was added. See
   `runtime-permissions-and-shutdown-audit.md`.

## Verification

- **48 script tests**: 25 artifact, 16 inventory, 4 gate, 3 Termux repack.
- Both existing Bash native tooling/inventory suites pass.
- **380 debug + 380 release JVM tests**, zero errors/failures.
- Both lint variants pass, **42 debug / 44 release warnings**; not warning-free.
- Debug/release APK, unsigned AAB and AndroidTest APK built.
- Actual ARM64 APK and AAB inspectors **pass**; `make release-check` **passes**.
- Both rebuilt native ABIs pass ELF machine/alignment/export/dynamic checks; no
  executable stack, text relocations, RPATH/RUNPATH or unexpected JNI exports.
- bundletool 1.18.3 (official release SHA verified) structurally validates the new
  AAB. Representative synthetic arm64/API36/420dpi/en unsigned APK-set download:
  **147,384,954 bytes**. Both required assets are stored uncompressed in base-master.
- AAB **147,577,537 bytes**; unsigned APK **403,939,179 bytes**. Fresh hashes,
  native provenance and measurements replace the previous snapshot in
  `../release/artifact-measurements.json`; resolved dependency graph refreshed.
- Scoped permission RED: 17 tests / 3 failures before edits; scoped GREEN: 20 pass.
  Native source/SDK resolution and script changes also have red/green evidence.
- No device instrumentation execution or 16 KB physical runtime certification.
  x86_64 native JNI is compiled/checked, not a rebuilt x86_64 guest APK acceptance.

Commands:

```sh
make verify-play-tools
bash scripts/tests/apk-inventory-test.sh
bash scripts/tests/runtime-tools-test.sh
cd android && ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest :app:bundleRelease --console=plain
# from repository root
make release-check
```

Logs: `/tmp/seed-play-engineering/{full-build.log,script-tests.log,release-check.log}`.
NDK was explicitly installed with already accepted SDK licenses; no license
acceptance command/new release key/account change occurred. SDK resolution respects
local.properties as well as AGP environment handling. APK-set generation used an
isolated empty user home, no debug/release signing key, no device-derived spec.

## Review and constraints

Independent full-file review found no critical engineering defect; stale manual
native fingerprints were corrected after the final Gradle build. Parent verification
confirmed actual outputs and permissions/tests. No source runtime archive/native
PRoot bundle or source seed_version metadata was regenerated. Its pre-existing
build-ID diff remains uncommitted. No uploads, app installs/uninstalls, photographs,
coordinates, credentials inspection, production Restore or migrations occurred.

## Still before publication

- Real 16 KB PRoot/guest/PTY behavior and signed new-package Android 15/16 device
  acceptance, including upgrade preservation and recovery. ELF success alone is
  not runtime compatibility or Play approval.
- FGS type justification and a separately designed/tested bounded timeout/user-stop
  implementation; current dataSync onTimeout is still absent.
- Unrestricted executable-code eligibility and AI safeguards/in-app reporting.
- Owner chose **GPL in principle**; exact version/only-or-later awaits confirmation
  before project LICENSE/SPDX metadata changes. Third-party licenses stay separate;
  Alpine/PRoot/talloc source/notice obligations are not cleared by that choice.
- Final privacy/provider/telemetry/deletion answers, policy publication/in-app link,
  signing/account/testing/Console declarations and owner upload approval.
- Logo remains deferred. Branch remains separate from main, not pushed/merged.

The earlier preparation report remains historical; this report supersedes its
Termux alignment failure, counts and artifact fingerprints, not its policy gates.
