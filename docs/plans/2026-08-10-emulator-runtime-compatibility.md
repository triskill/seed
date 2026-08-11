# Emulator Runtime Compatibility Implementation Plan

> **2026-08-11 compatibility update:** Tasks 1–4 below record the original
> extraction-hard-link and architecture-preflight work. That work moved the
> emulator failure from rootfs extraction/ABI mismatch to Android's writable
> executable restriction. Android 10+ forbids apps targeting API 29+ from
> executing files copied into writable app home, and `chmod` cannot bypass that
> W^X policy. The follow-up
> [`2026-08-11-native-proot-packaging.md`](2026-08-11-native-proot-packaging.md)
> packages one generated proot at a time as
> `jniLibs/x86_64/libproot.so` or `jniLibs/arm64-v8a/libproot.so`; the app uses
> PackageManager/AGP legacy JNI extraction and resolves
> `applicationInfo.nativeLibraryDir/libproot.so`. Only `rootfs.tar.gz` and
> `seed_version.json` remain writable-extraction assets. `make run` now
> preflights the exact selected `jniLibs` path and still never auto-builds the
> runtime. Native packaging implementation is present, but emulator/backend
> health acceptance is not recorded as complete here.

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Prevent Android rootfs extraction from crashing and make `make run` fail before installation when the bundled runtime does not match the x86_64 development emulator.

**Architecture:** Materialize TAR hard-link entries as ordinary copied files because Android's `untrusted_app` SELinux domain denies `link(2)`. Parameterize runtime generation with `RUNTIME_ARCH=arm64|x86_64`; Docker buildx selects `linux/arm64` or `linux/amd64` from the multi-platform Alpine base, requiring QEMU/binfmt only for a cross-architecture build. Stage and validate a complete bundle before publishing `seed_version.json` last. Add a small preflight script used by `make run` to compare the emulator ABI with the packaged proot ELF architecture. The preflight prints the exact explicit runtime-build command on mismatch; it never starts a large Docker build automatically.

**Tech Stack:** Kotlin/JVM, Commons Compress, Bash, GNU `file`, Android Gradle, Make.

---

### Task 1: Materialize TAR hard links safely

**Files:**
- Modify: `android/app/src/test/java/com/seed/app/runtime/RuntimeExtractorTest.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/RuntimeExtractor.kt`

1. Change the existing rootfs link test to require hard-link content to be copied into a distinct inode, documenting the Android SELinux constraint.
2. Run `cd android && ./gradlew :app:testDebugUnitTest --tests com.seed.app.runtime.RuntimeExtractorTest.rootfsTarIsExpandedWithLinksAndExecutableModes`; expect failure because `Files.isSameFile` is still true.
3. Replace `Files.createLink` with a no-follow copy from the already validated, extracted regular-file target; preserve the target's executable mode.
4. Re-run the focused test and all `RuntimeExtractorTest` tests; expect success.

### Task 2: Add architecture-aware runtime generation

**Files:**
- Create: `scripts/runtime-target.sh`
- Create: `scripts/tests/runtime-tools-test.sh`
- Modify: `scripts/build-runtime.sh`

1. Add shell tests asserting arm64 and x86_64 configuration values and unsupported-architecture rejection.
2. Run `bash scripts/tests/runtime-tools-test.sh`; expect failure because `runtime-target.sh` does not exist.
3. Implement `configure_runtime_target`, selecting the pinned proot URL, Alpine URL/SHA, Docker platform/base image, and ELF architecture marker for each supported architecture.
4. Source the helper from `build-runtime.sh`, default `RUNTIME_ARCH` to `arm64`, and use selected values in download, validation, Docker build/export, and image inspection.
5. Re-run the shell tests and `bash -n` for all modified scripts.

### Task 3: Fail fast from `make run`

**Files:**
- Create: `scripts/check-runtime-arch.sh`
- Modify: `scripts/tests/runtime-tools-test.sh`
- Modify: `Makefile`

1. Add shell tests using controlled fake runtime files and a fake `file` command that reports x86_64 or arm64 ELF descriptions. Verify matching checks succeed; an arm64 expectation against an x86_64 runtime must fail with `make runtime RUNTIME_ARCH=arm64`, and an x86_64 expectation against an arm64 runtime must fail with `make runtime RUNTIME_ARCH=x86_64`.
2. Run the shell tests; expect failure because the checker does not exist.
3. Implement architecture detection using `file -Lb`, with clear errors for missing, non-ELF, unsupported, and mismatched runtime files.
4. Add a `runtime` Make target that invokes `scripts/build-runtime.sh`, and a `check-runtime-arch` prerequisite for `run` using the emulator ABI derived from `SYSTEM_IMAGE`.
5. Verify `make check-runtime-arch` fails against the current arm64 proot before starting/installing anything and prints `make runtime RUNTIME_ARCH=x86_64`.

### Task 4: Documentation and verification

**Files:**
- Modify: `README.md`
- Modify: `android/README.md`
- Modify: `docs/build-runtime.md`

1. Document explicit arm64 and x86_64 runtime build commands and the fail-fast behavior of `make run`.
2. Run Android unit tests and APK assembly.
3. Run backend/webapp tests and shell tooling tests.
4. Confirm `make run` exits non-zero before emulator launch with the actionable architecture mismatch message when arm64 assets are packaged for the x86_64 AVD.
