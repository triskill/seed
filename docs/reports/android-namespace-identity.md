# Android namespace identity change

Base: `main` at `34fb728`. User confirmed ownership of trety.cz and approved
exact namespace AND application ID `cz.trety.seed`.

## Scope and safety

Mechanical package rename only: Gradle namespace/applicationId, main/debug/test/
androidTest Kotlin declarations, imports, R/BuildConfig references, maintenance
action, explicit debug fixture manifest, and Makefile app/component IDs.
All four package trees were moved with `git mv` to `java/cz/trety/seed`.
README files and current TODO source links describe the new identity.
Relative main manifest application/activity/service names resolve to the new
namespace; no new permissions, authorities, endpoints, or dependencies added.

This is a new Android app, not an upgrade of `com.seed.app`. Existing old
installations remain separate: preserve all their data; do not uninstall or
clear them. No migration is implemented for private runtime/generated-app data,
settings, credentials/Keystore keys, or WebView browser state. The new app cannot
access the old app's private files or keys. Runtime Restore remains unchanged;
it does not automatically migrate another package. No ADB/device installation,
uninstallation, or data operations were performed. No device acceptance claimed.

The pre-existing uncommitted `android/app/src/main/assets/linux/seed_version.json`
was untouched. Ignored native libraries/rootfs binaries were not edited/scanned.
Historical plans and acceptance reports retain their original IDs and evidence.

## Regression and verification

`RotationManifestTest.androidIdentityAndSourceSetsUseApprovedNamespace` verifies
Gradle namespace/applicationId, generated BuildConfig identity and R package,
all four source-set trees/declarations, absence of legacy package trees, and the
qualified debug fixture. Existing rotation policy contract remains intact.

Before implementation:

```sh
cd android
./gradlew :app:testDebugUnitTest --tests com.seed.app.ui.app.RotationManifestTest
```

Observed RED: 2 tests, 1 expected assertion failure at namespace contract line 13.

After implementation:

```sh
cd android
./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

Observed BUILD SUCCESSFUL. Debug: 368 tests, 0 failures/errors/skips. Release:
368 tests, 0 failures/errors/skips. Lint debug: 0 errors, 40 warnings; release:
0 errors, 42 warnings. Compiler deprecation/unused/nullable warnings remain.
AndroidTest was compiled/packaged only, not executed.

Using `/home/borbot/android-sdk/build-tools/34.0.0/aapt dump badging` on:

- `android/app/build/outputs/apk/debug/app-debug.apk`: package `cz.trety.seed`,
  launcher `cz.trety.seed.MainActivity`, versionCode 1 / versionName 0.1.0.
- `android/app/build/outputs/apk/release/app-release-unsigned.apk`: same identity,
  launcher and version. Release is unsigned.
- `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`:
  package `cz.trety.seed.test`.

Initial tracked text audit found 151 files with dotted identity or slash package
paths (135 Android/Makefile files, TODO, 14 historical plans, one historical
report). Remaining legacy references are historical plans/report, explicit data
warnings, this RED command, and the regression's forbidden legacy-tree check.
Generated merged XML manifests and all three APK manifests contain no old ID.
`aapt dump xmltree <apk> AndroidManifest.xml` additionally confirms
`cz.trety.seed.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
`cz.trety.seed.androidx-startup` authority, qualified application/service names,
and AndroidTest `targetPackage="cz.trety.seed"`. Output metadata confirms both
app variants use the exact unsuffixed ID. Active scripts/tools/backend/
webapp contain no old identity dependency; backend/SDK suites were not rerun
because their code, protocols, endpoints, and dependencies did not change.

No commit made; parent review/commit remains required.
