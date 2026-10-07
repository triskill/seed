# Google Play preparation execution report

Date: **2026-10-07**. Branch: **app-store-preparation**, starting from `8979b80`.
Plan: `../plans/2026-10-07-app-store-preparation.md`.

**Outcome: local preparation executed; public beta publication remains blocked.**
No upload, Console/account operation, new release key, device install/uninstall,
production Restore or user-data migration was performed. `main` was not merged.
The pre-existing `seed_version.json` build-ID modification is untouched/uncommitted.

## Implemented

- Evidence-backed current Play audit with official sources: API 36 target requirement,
  interpreted/native executable policy uncertainty, AI in-app reporting, foreground
  service, current size/page guidance, privacy and account-dependent testing.
- Compile/target **API 36**, minimum SDK **26**, AGP **8.10.1**, Gradle **8.11.1**
  with official distribution checksum; CI/local SDK provisioning aligned. Existing
  Kotlin/Compose and API 34 AVD compatibility target retained.
- Single ABI derived from public runtime metadata: arm64 → arm64-v8a, x86_64 →
  x86_64. Filters transitive AAR native libraries too. Before this, Termux supplied
  four ABIs despite only one rootfs. Current debug/release APK and AAB are ARM64-only.
- `make bundle-release`, `make check-play-artifact`, `make verify-play-tools`.
  AAB remains **unsigned**, package **cz.trety.seed**, version **0.1.0 / code 1**.
- Read-only ZIP/ELF inspector with bounded directory/native processing, exact ABI,
  required runtime libraries, metadata validation, APK openFd storage checks,
  malformed-file/credential-path checks and explicit limitations. Rootfs contents
  and all secrets/provenance are not cleared by a filename/inventory scan.
- Declared/resolved dependency and actual guest metadata evidence: 65 Alpine,
  49 Python and 311 npm records. Not a complete legally cleared SBOM/source offer.
- Owner-review drafts for privacy, Data Safety and store listing. README's obsolete
  blanket Keystore credential claim corrected; current Pi auth is plain private
  JSON, and role sessions/telemetry-package presence require honest disclosures.

## Verification

| Check | Result |
| --- | --- |
| Inspector fixtures | **25 passed**, including red/green corrupted compression, deep JSON, directory/read budgets, asset-path ABI false positives and capped diagnostics |
| Existing native script suites | Both passed |
| Debug / release JVM | **373 + 373**, zero failures/errors |
| Debug / release lint | Passed, **50 / 51 warnings**, not warning-free |
| Debug / unsigned release APK | Built |
| AndroidTest APK | Compiled; **not executed** after namespace/API changes |
| Unsigned release AAB | Built; jarsigner confirms unsigned |
| Backend + webapp / scoped Ruff / mypy | **310 passed**, one warning; scoped static checks passed |
| Actual artifact inventory | **FAIL**, exactly one remaining finding: ARM64 libtermux.so PT_LOAD alignment |
| bundletool 1.18.3 structural validation | Passed; package cz.trety.seed, target 36, min 26 |
| Delivered openFd assets | Both rootfs.tar and seed_version.json ZIP_STORED in generated base-master split |
| Representative compressed delivery | **147,384,690 bytes**, synthetic arm64/API36/420dpi/en specification |

The inspector gate is intentionally not waived. All four PRoot/support libraries
pass the scoped ELF alignment check; **Termux JNI libtermux.so does not**. The
checker cannot establish guest executable behavior or physical 16 KB compatibility.

`../release/artifact-measurements.json` ties measurements to artifact hashes.
Raw unsigned AAB: **147,577,471 bytes**; unsigned APK: **403,939,107 bytes**;
rootfs TAR: **389,949,952 bytes**. Different devices/delivery configurations or
regeneration can differ. No claim of all-device maximum size, update patch size,
installed size or Play acceptance is made.

### Commands and private local logs

```sh
python3 -m unittest discover -s scripts/tests -p 'test_play_artifact.py' -v
bash scripts/tests/apk-inventory-test.sh
bash scripts/tests/runtime-tools-test.sh
make verify-python verify-python-static
cd android && ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest \
  :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease \
  :app:assembleDebugAndroidTest :app:bundleRelease --console=plain
```

Local final verification: `/tmp/seed-play-preparation/{final-build.log,
inspector-tests.log,python-verification.log}`. SDK build/red ABI proof:
`/tmp/seed-play-sdk-build.log`, `/tmp/seed-play-abi-red.log`. These are not CI runs.
The latest failing remote CI has not been diagnosed or rerun by this branch.

Official bundletool release JAR was downloaded locally and its SHA-256 compared
with the GitHub release asset digest. `validate`, `dump manifest`, `dump config`,
`build-apks` and `get-size total` were used. APK-set generation used an isolated
empty HOME/Android user directory, not the developer debug key or a release key;
bundletool explicitly warned the APKs were unsigned/not installable. No device
specification was read from the phone: the representative JSON is synthetic.

## Review corrections

Independent review caught corrupt-DEFLATE/deep-JSON exceptions, overly broad
interior-lib ABI discovery and insufficient whole-archive guardrails. Regression
fixtures observed failures then passed after correction. Parent review corrected
an important distinction: deflated assets in an AAB ZIP are valid; final delivered
APK storage is governed by BundleConfig, verified separately with bundletool.
Capped finding diagnostics also received a failing-then-passing regression.

## Remaining gates — not completed by preparation

1. **Replace/rebuild the exact Termux JNI with verified 16 KB-compatible code**, then
   test PRoot/guest runtime and all dependencies on a real/emulated 16 KB Android.
   Do not patch ELF header alignment to create a false pass.
2. **Resolve Play execution-policy eligibility**. Accepted unrestricted agents can
   execute/download native code; prompt rules and the interpreter exception do not
   clear the full behavior. Decide compatible product scope or distribution route;
   no isolation/restrictions were added silently.
3. **AI content safeguards and in-app reporting**, plus required reviewer experience.
   A support email outside the app alone is not the stated reporting mechanism.
4. **Foreground-service suitability and timeout lifecycle**. Current dataSync has
   no onTimeout handler; API 35+ background budgets and safe owned-runtime shutdown
   require implementation/device acceptance and an appropriate Console declaration.
5. **Licensing/notices/corresponding source** and project license chosen by the owner.
   Inventories alone grant no redistribution permission.
6. **Approve/publish privacy and Data Safety**, define real contacts/provider terms,
   verify telemetry/network/logging/retention/deletion and expose policy in the app.
7. **Owner account/signing/testing/publication**. Verify account type, access gates,
   upload-key custody, Play App Signing and any closed-test requirement before an
   open beta. Owner approves branding, assets, audience, ratings and final upload.
8. **Actual signed-release acceptance** on the new package and API 35/36, including
   first install, provider/agent flow, permissions, rotation, foreground/background
   behavior, update preservation and Restore. Earlier 52 old-package debug device
   tests are not this acceptance. No browser/auth/app data migration is provided.

See `../release/google-play-readiness.md`, dependency inventory and draft disclosures
for detailed evidence and owner-dependent questions. The execution plan's local
preparation scope is complete; these gates must close before publication.
