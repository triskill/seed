# Native proot Packaging Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Make the embedded proot executable start on Android 10+ by packaging it in Android's read-only native-library directory instead of copying it into execution-denied app data.

**Architecture:** Runtime generation publishes the selected proot as `jniLibs/<abi>/libproot.so`, while rootfs/version stay in assets. Android extracts the native file at install time, `RuntimeService` resolves it through `applicationInfo.nativeLibraryDir`, and Make validates the matching source `jniLibs` file before Gradle or emulator startup. The writable extraction path contains data and interpreted scripts only.

**Tech Stack:** Kotlin/JVM, Android Gradle Plugin, Android PackageManager native libraries, Bash, Make, GNU `file`, ADB/Logcat.

---

### Task 1: Resolve proot from Android's native-library directory

**Files:**
- Create: `android/app/src/main/java/com/seed/app/runtime/NativeProot.kt`
- Create: `android/app/src/test/java/com/seed/app/runtime/NativeProotTest.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/RuntimeService.kt`
- Modify: `android/app/build.gradle.kts`
- Modify: `android/app/src/main/AndroidManifest.xml`

1. Write a JVM test asserting `NativeProot.executable(nativeLibraryDir)` resolves exactly `<nativeLibraryDir>/libproot.so`, rejects a missing/non-file binary with a clear message, and never points under `filesDir`.
2. Run `cd android && ./gradlew :app:testDebugUnitTest --tests com.seed.app.runtime.NativeProotTest`; expect compilation failure because `NativeProot` does not exist.
3. Add the minimal resolver and make `RuntimeService` pass `applicationInfo.nativeLibraryDir` to it instead of constructing `filesDir/linux/proot`.
4. Enable extracted native-library packaging with `packaging.jniLibs.useLegacyPackaging = true` and `android:extractNativeLibs="true"`, so PackageManager installs the native executable as a real file.
5. Re-run focused tests and all Android unit tests; expect success.
6. Commit the focused Android path/packaging change.

### Task 2: Publish proot into architecture-specific jniLibs

**Files:**
- Modify: `scripts/runtime-target.sh`
- Modify: `scripts/build-runtime.sh`
- Modify: `scripts/tests/runtime-tools-test.sh`
- Modify: `.gitignore`

1. Add failing shell assertions that arm64 maps to `arm64-v8a/libproot.so`, x86_64 maps to `x86_64/libproot.so`, generated native binaries are ignored, and successful architecture switching removes the opposite ABI and legacy `assets/linux/proot` only after all validation succeeds.
2. Extend the existing failure-preservation fixture to prove a failed build leaves current native proot, opposite ABI, rootfs, and marker untouched. Run `bash scripts/tests/runtime-tools-test.sh`; expect the new assertions to fail.
3. Export `ANDROID_ABI` and `PROOT_JNI_RELATIVE_PATH` from `configure_runtime_target`.
4. Add configurable `JNI_LIBS_DIR` (default `android/app/src/main/jniLibs`) to `build-runtime.sh`. Validate/reuse/download proot at the selected native destination, stage it safely, create destination directories only for publication, remove the opposite ABI and legacy asset on successful publication, and keep `seed_version.json` last.
5. Update comments/output and `.gitignore` for generated `jniLibs/*/libproot.so` without broadly ignoring source-controlled native code.
6. Run all shell tests, Bash syntax checks, and failure-preservation tests; expect success.
7. Commit the architecture-aware native publication change.

### Task 3: Stop extracting writable executables

**Files:**
- Modify: `android/app/src/main/java/com/seed/app/runtime/AssetSource.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/AndroidAssetSource.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/RuntimeExtractor.kt`
- Modify: `android/app/src/test/java/com/seed/app/runtime/RuntimeExtractorTest.kt`
- Modify: `android/app/src/test/java/com/seed/app/runtime/BootControllerTest.kt`
- Modify: `android/app/build.gradle.kts`

1. Change tests/fakes so `AssetEntry` no longer has an executable flag and remove the test that expects a copied proot asset to become executable. Keep TAR mode tests because interpreted rootfs files still preserve archive metadata.
2. Run focused extractor tests; expect compilation failures while production still requires `AssetEntry.executable`.
3. Remove the executable field and asset-copy chmod path. Update `AndroidAssetSource` to describe/list only rootfs and version data, and remove `linux/proot` from `noCompress`.
4. Re-run extractor, boot-controller, and full Android unit tests; expect success.
5. Commit the extraction-boundary change.

### Task 4: Point Make preflight at packaged native proot

**Files:**
- Modify: `Makefile`
- Modify: `scripts/check-runtime-arch.sh`
- Modify: `scripts/tests/runtime-tools-test.sh`

1. Add failing tests asserting `make check-runtime-arch` passes `android/app/src/main/jniLibs/x86_64/libproot.so` for the configured x86_64 image, arm64-v8a path mapping is correct, the checker has no obsolete assets default, and failed preflight still prevents Gradle/emulator launch.
2. Run shell tests; expect Make wiring assertions to fail.
3. Derive the Android ABI from `SYSTEM_IMAGE`, build the explicit jniLibs proot path, and require both expected architecture and path in `check-runtime-arch.sh`.
4. Re-run all shell tests, injection tests, parallel Make tests, `make -n`, Bash syntax, and `git diff --check`; expect success.
5. Commit the preflight-path change.

### Task 5: Document and verify on API 34 emulator

**Files:**
- Modify: `README.md`
- Modify: `android/README.md`
- Modify: `docs/build-runtime.md`
- Modify: `docs/plans/2026-08-10-emulator-runtime-compatibility.md`

1. Update documentation to distinguish native proot packaging from rootfs assets, list ABI destinations, explain Android's API 29 W^X restriction, and retain explicit runtime build commands.
2. Locally reuse the already generated x86_64 proot by copying it to `android/app/src/main/jniLibs/x86_64/libproot.so`; do not commit generated binaries or rerun the large Docker build solely for this migration.
3. Run `bash scripts/tests/runtime-tools-test.sh`, `cd android && ./gradlew :app:testDebugUnitTest --rerun-tasks :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`, and backend/webapp tests.
4. Inspect the APK with `unzip -l` and require `lib/x86_64/libproot.so` with no `assets/linux/proot`.
5. Run `make run`, inspect `applicationInfo.nativeLibraryDir`/file SELinux label with ADB, and verify Logcat no longer contains `execute_no_trans` for proot.
6. Forward emulator port 7777 with ADB and require the backend health endpoint to respond successfully. If proot starts but a later runtime failure appears, return to systematic debugging rather than weakening this acceptance criterion.
7. Commit documentation, request final review, and preserve the worktree until the user selects merge/PR handling.
