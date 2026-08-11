# Native proot Packaging Design

## Problem

Android 10 and later deny `execve(2)` for files in an app's writable home directory when the app targets API 29 or later. Seed previously copied `proot` from APK assets to `filesDir/linux/proot`, set its executable bit, and started it with `ProcessBuilder`. On the API 34 x86_64 emulator this reached SELinux as `app_data_file` and failed with `execute_no_trans`, even though the mode was executable. This is Android's W^X policy, not a Unix permission-bit or architecture failure; `chmod` cannot bypass it.

## Architecture

Package proot through Android's native-library mechanism rather than as an ordinary asset. Runtime generation writes the selected executable as `android/app/src/main/jniLibs/<android-abi>/libproot.so`, where arm64 maps to `arm64-v8a` and x86_64 maps to `x86_64`. Only one ABI is published at a time. The app enables extracted native-library packaging and resolves the installed executable through `applicationInfo.nativeLibraryDir`; it never copies or modifies proot at runtime.

The architecture-specific rootfs remains `assets/linux/rootfs.tar.gz`, and `seed_version.json` remains the extraction completion marker. `AndroidAssetSource` handles only rootfs/version assets. Runtime generation stages and validates the proot and rootfs before publishing, removes a stale opposite-ABI proot during a successful architecture switch, and publishes the version marker last. `make run` checks the selected `jniLibs` proot before Gradle or emulator startup and continues to print the explicit matching runtime-build command.

## Failure Handling and Verification

Missing or mismatched native proot files fail during Make preflight. A missing installed native executable produces a clear runtime failure instead of falling back to writable app storage. JVM tests cover path resolution and asset extraction boundaries; shell tests cover ABI mapping, publication, and Make wiring. Remaining acceptance must inspect the APK for `lib/<abi>/libproot.so`, then use an API 34 emulator to check the installed native-library path/label, proot startup, and backend health. Those emulator/backend checks are not recorded as complete yet. Lowering `targetSdk` or bypassing policy with a writable executable is explicitly out of scope.
