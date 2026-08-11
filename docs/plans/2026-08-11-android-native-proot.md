# Android-Native PRoot Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Replace generic Linux PRoot binaries with a checksum-pinned Android-native Termux bundle that starts successfully from Android's Zygote-spawned `untrusted_app` domain.

**Architecture:** Runtime generation extracts PRoot, its external loader, libtalloc, and libandroid-shmem from pinned Termux Debian packages into one architecture-specific `jniLibs` directory. Android validates the complete installation and supplies both the loader path and native dependency search path to the managed PRoot process.

**Tech Stack:** Bash, Termux Debian packages, ELF/readelf validation, Android JNI packaging, Kotlin, JUnit, Android instrumentation, Gradle, adb.

---

### Task 1: Pin Android-native package metadata

**Files:**
- Modify: `scripts/runtime-target.sh`
- Modify: `scripts/tests/runtime-tools-test.sh`

**Step 1: Write failing target-configuration tests**

Replace generic `PROOT_URL`, embedded-loader offset, and embedded-loader size expectations with package metadata for `proot` 5.1.107.89, `libtalloc` 2.4.3, and `libandroid-shmem` 0.7. Assert paths for all four final native files and package/final checksums.

Pinned package SHA-256 values:

```text
x86_64 proot package: 0d76da0515f38dfb2217f647b0d79fcd61b38f80e25cbf2d39237697b02dd016
x86_64 talloc package: 7ca2eaae2e53b28228a01301bc410b62845403d6317c25b8e0a7f40681de0628
x86_64 shmem package: ffa9e4c87467b158b148d0ff92dda796aa038276c2075af3269cdcdb06f25797
arm64 proot package: ec9fe38c50cfd49dd31fe360ffbcc3124a945dc1ea16293a8a769303dd724f46
arm64 talloc package: ac81ad623d74c209718b9f3acb2dd702cc8a88c431e820d212229910b4db29da
arm64 shmem package: 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6
```

Pinned final artifact SHA-256 values after rewriting `libtalloc.so.2` to `libtalloc.so` in PRoot:

```text
x86_64 libproot.so: d87c0bd62dfbd456826e8c3f968d4e9b264e6a912417e40a883900142d867051
x86_64 loader: 914564ea1c66f50b38f18cac857fcf814c6b1ab027789178880fca1d530599b3
x86_64 libtalloc.so: 77be445f4ec245fff9c19e9874ebcf99618244cf48737f5fca938316daaa70da
x86_64 libandroid-shmem.so: 092926060298acd3778e6239033d7aef1280dcb59aebe021a3719612e6a3465f
arm64 libproot.so: 7da118895e971ea9fba4bb250b28af0f8db2edcbfdbaa8075cc645a0d7cf16fe
arm64 loader: 44ef39c1e1a18c09f6e4c4b5d6f8bba82d30596598bd155ec162d05c5122ff04
arm64 libtalloc.so: 3c9b207c0a6ea2896b7523e03f55d9ab0d9e88baa115d4c32b84058ff4246fbb
arm64 libandroid-shmem.so: 84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731
```

**Step 2: Run focused shell tests and confirm failure**

Run:

```bash
RUNTIME_TOOLS_TEST_FILTER='target configuration' scripts/tests/runtime-tools-test.sh
```

Expected: FAIL because `runtime-target.sh` still exports generic static-release metadata.

**Step 3: Implement target metadata**

Use base URL `https://packages.termux.dev/apt/termux-main` and architecture-specific package paths. Export package URLs/checksums, final artifact checksums, `TALLOC_JNI_RELATIVE_PATH`, and `ANDROID_SHMEM_JNI_RELATIVE_PATH`. Retain Alpine/Docker mappings and the existing `arm64`/`x86_64` public target names.

**Step 4: Run focused tests**

Expected: both target configuration tests PASS.

**Step 5: Commit**

```bash
git add scripts/runtime-target.sh scripts/tests/runtime-tools-test.sh
git commit -m "build(runtime): pin Android-native proot packages"
```

### Task 2: Generate and publish the complete native bundle

**Files:**
- Modify: `scripts/build-runtime.sh`
- Modify: `scripts/tests/runtime-tools-test.sh`

**Step 1: Replace old builder tests with failing bundle tests**

Cover these observable behaviors:

1. `ar` and `readelf` are declared prerequisites; `dd` is no longer required.
2. Each package is checksum-checked before extraction.
3. PRoot is extracted from `data/data/com.termux/files/usr/bin/proot`.
4. Loader is extracted from `data/data/com.termux/files/usr/libexec/proot/loader`.
5. Talloc is renamed from `libtalloc.so.2.4.3` to `libtalloc.so`.
6. The PRoot byte sequence `libtalloc.so.2\0` is replaced exactly once by the equal-length `libtalloc.so\0\0\0` sequence.
7. `readelf -d` reports `libtalloc.so` and `libandroid-shmem.so`, and does not report `libtalloc.so.2`.
8. Existing valid four-file bundles are reused only as a complete unit.
9. Invalid or partial selected bundles are replaced from staged packages.
10. Successful architecture switching removes all four opposite-ABI artifacts.
11. Package, rewrite, ELF, or final-checksum failures preserve the prior bundle and marker.
12. `seed_version.json` is still published last.

Use fake `curl`, `ar`, `tar`, `file`, `readelf`, `sha256sum`, `docker`, and `uv` tools so focused tests do not access the network or build the rootfs.

**Step 2: Run focused tests and confirm failure**

```bash
RUNTIME_TOOLS_TEST_FILTER='native bundle' scripts/tests/runtime-tools-test.sh
```

Expected: FAIL against the embedded-loader builder.

**Step 3: Implement package extraction and validation**

Add helpers equivalent to:

```bash
extract_deb_data() {
    local package="$1" destination="$2" archive
    archive="$(ar t "$package" | grep -E '^data\.tar\.(xz|gz|zst|bz2)$')"
    [[ -n "$archive" ]] || return 1
    ar p "$package" "$archive" | tar -x -C "$destination"
}

rewrite_talloc_needed() {
    python3 - "$1" <<'PY'
from pathlib import Path
import sys
path = Path(sys.argv[1])
data = path.read_bytes()
old = b"libtalloc.so.2\0"
new = b"libtalloc.so\0\0\0"
if data.count(old) != 1:
    raise SystemExit("expected exactly one libtalloc.so.2 dependency")
path.write_bytes(data.replace(old, new))
PY
}
```

The actual implementation must avoid extracting untrusted package paths into destination roots: extract into a private temporary package directory, then copy only the four known regular files into the same-filesystem JNI staging directory. Validate all package checksums first, all final artifacts after rewrite, ELF architecture using `file`, loader static executable type, and PRoot dynamic dependencies using `readelf`.

Publish all four staged files only after rootfs generation succeeds. Remove all four opposite-ABI generated files, then publish rootfs and marker in the existing marker-last order.

**Step 4: Run shell tests**

```bash
scripts/tests/runtime-tools-test.sh
bash -n scripts/build-runtime.sh scripts/runtime-target.sh
```

Expected: all shell tests PASS; syntax checks exit 0.

**Step 5: Commit**

```bash
git add scripts/build-runtime.sh scripts/tests/runtime-tools-test.sh
git commit -m "build(runtime): package Android-native proot bundle"
```

### Task 3: Preflight and ignore all generated native artifacts

**Files:**
- Modify: `.gitignore`
- Modify: `Makefile`
- Modify: `scripts/tests/runtime-tools-test.sh`

**Step 1: Write failing tests**

Assert exact repository ignore rules for `libtalloc.so` and `libandroid-shmem.so` under both ABIs. Update Make dry-run/call-count tests to require architecture checking of all four files. Add missing/mismatched dependency tests proving Gradle and emulator do not start.

**Step 2: Run focused tests and confirm failure**

```bash
RUNTIME_TOOLS_TEST_FILTER='preflight' scripts/tests/runtime-tools-test.sh
RUNTIME_TOOLS_TEST_FILTER='ignore rules' scripts/tests/runtime-tools-test.sh
```

Expected: FAIL because only PRoot and loader are covered.

**Step 3: Implement four-file preflight and exact ignore rules**

Append exact ignore paths for both dependencies and invoke `check-runtime-arch.sh` for both dependency files after PRoot and loader. Keep all invocations in one `&&`-gated shell recipe.

**Step 4: Run shell tests**

```bash
scripts/tests/runtime-tools-test.sh
make -n check-runtime-arch 'SYSTEM_IMAGE=system-images;android-34;default;x86_64'
make -n run
```

Expected: tests PASS; dry runs show four checks and no runtime build.

**Step 5: Commit**

```bash
git add .gitignore Makefile scripts/tests/runtime-tools-test.sh
git commit -m "fix(runtime): preflight complete native proot bundle"
```

### Task 4: Resolve the complete Android installation and dependency path

**Files:**
- Modify: `android/app/src/main/java/com/seed/app/runtime/NativeProot.kt`
- Modify: `android/app/src/main/java/com/seed/app/runtime/ProotEnvironment.kt`
- Modify call sites found by `rg 'ProotEnvironment.create|NativeProotInstallation' android/app/src`
- Modify: `android/app/src/test/java/com/seed/app/runtime/NativeProotTest.kt`
- Modify: `android/app/src/test/java/com/seed/app/runtime/ProotEnvironmentTest.kt`

**Step 1: Write failing Kotlin unit tests**

Construct all four files and expect:

```kotlin
NativeProotInstallation(
    executable = executable,
    loader = loader,
    talloc = talloc,
    androidShmem = androidShmem,
)
```

Add missing/directory rejection tests for each dependency. Update the exact environment assertion to include:

```kotlin
"LD_LIBRARY_PATH" to loader.parentFile!!.absolutePath
```

**Step 2: Run focused tests and confirm failure**

```bash
cd android
./gradlew testDebugUnitTest \
  --tests com.seed.app.runtime.NativeProotTest \
  --tests com.seed.app.runtime.ProotEnvironmentTest
```

Expected: compilation/assertion failures because the installation contains only two files and no library path.

**Step 3: Implement minimal Android changes**

Extend `NativeProotInstallation` with `talloc` and `androidShmem`; validate `libtalloc.so` and `libandroid-shmem.so` as regular files. Make `ProotEnvironment.create` accept the installation rather than a loader alone, validate that all files share one native directory, and set `PROOT_LOADER` plus `LD_LIBRARY_PATH`. Update production call sites and tests.

**Step 4: Run Android unit tests**

```bash
cd android
./gradlew testDebugUnitTest
```

Expected: all unit tests PASS.

**Step 5: Commit**

```bash
git add android/app/src/main android/app/src/test
git commit -m "fix(android): load Android-native proot dependencies"
```

### Task 5: Preserve an app-domain regression smoke test

**Files:**
- Rename/modify: `android/app/src/androidTest/java/com/seed/app/runtime/DiagnosticProotTest.kt` to `NativeProotSmokeTest.kt`

**Step 1: Turn the diagnostic into a stable regression test**

The test must:

1. Assert `/proc/self/attr/current` contains `untrusted_app`.
2. Resolve the four-file `NativeProotInstallation`.
3. Ensure the packaged rootfs has been extracted using production extraction APIs, rather than relying on prior device state.
4. Start PRoot with `ProotEnvironment.create`.
5. Run guest `/usr/bin/python3 -c "print('APP_DOMAIN_PROOT_OK')"`.
6. Concurrently drain stdout/stderr, enforce a bounded timeout, and report domain, exit code, stdout, and stderr on failure.
7. Assert exit code 0 and exact success output.

This test fails with the old generic x86_64 PRoot as `code=159` and passes with the Android-native bundle.

**Step 2: Run it on the x86_64 emulator**

```bash
cd android
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.seed.app.runtime.NativeProotSmokeTest
```

Expected: PASS under `u:r:untrusted_app:...`.

**Step 3: Commit**

```bash
git add android/app/src/androidTest/java/com/seed/app/runtime
git commit -m "test(android): exercise proot in app seccomp domain"
```

### Task 6: Rebuild and perform app-managed emulator acceptance

**Files:**
- Generated only: `android/app/src/main/jniLibs/x86_64/*`
- Generated only: `android/app/src/main/assets/linux/rootfs.tar.gz`
- Preserve unstaged: `android/app/src/main/assets/linux/seed_version.json`

**Step 1: Rebuild the x86_64 runtime**

```bash
make runtime RUNTIME_ARCH=x86_64
```

Expected: final output lists all four validated x86_64 native artifacts and rootfs.

**Step 2: Verify APK native contents**

```bash
make build
unzip -l android/app/build/outputs/apk/debug/app-debug.apk | grep 'lib/x86_64/'
```

Expected: exactly the four generated native files are present for x86_64.

**Step 3: Install from a clean app state and launch**

```bash
adb shell am force-stop com.seed.app || true
adb uninstall com.seed.app || true
adb install android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.seed.app/com.seed.app.MainActivity
```

Wait for extraction and managed startup while collecting focused Logcat.

**Step 4: Verify managed health**

```bash
adb shell 'for i in $(seq 1 120); do toybox nc -z 127.0.0.1 7777 && exit 0; sleep 1; done; exit 1'
adb shell 'toybox wget -qO- http://127.0.0.1:7777/health'
```

Expected: listener appears and health returns JSON with `"status":"ok"`. Confirm PRoot/Python remain child processes of the app-managed runtime, not a manual `run-as` launch.

### Task 7: Documentation and full verification

**Files:**
- Modify: `docs/build-runtime.md`
- Modify: `android/README.md`
- Modify any native-packaging plan whose current-state section still describes the two-file generic bundle

**Step 1: Update documentation**

Document the four-file bundle, Termux provenance and pinned versions, Android seccomp reason, `LD_LIBRARY_PATH`, four-file preflight, architecture switching, prerequisites (`ar`, `readelf`, Python), and GPL/source obligations/link to the exact Termux PRoot source version.

**Step 2: Run complete verification**

```bash
scripts/tests/runtime-tools-test.sh
bash -n scripts/build-runtime.sh scripts/runtime-target.sh scripts/check-runtime-arch.sh
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
cd ..
.venv/bin/python -m pytest backend/ webapp/
git diff --check
git status --short
```

Expected: all commands PASS. `seed_version.json` may remain intentionally modified; generated rootfs and all four native artifacts remain ignored.

**Step 3: Request code review and address only verified findings**

Use the requesting-code-review skill. Review runtime artifact integrity/publication, Android linker environment, app-domain test reliability, security, and documentation.

**Step 4: Commit documentation**

```bash
git add docs/build-runtime.md android/README.md docs/plans
git commit -m "docs(runtime): document Android-native proot bundle"
```
