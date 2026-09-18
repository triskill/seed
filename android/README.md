# Seed Android app

The Android shell contains four Compose screens (App, Chat, Shell, Settings),
extracts the bundled Alpine rootfs on first launch, starts it through a
natively packaged proot in a foreground service, and waits for the loopback
backend before showing the main navigation.

Embedded endpoints are the defaults:

- FastAPI backend: `http://127.0.0.1:7777/`
- Flask webapp: `http://127.0.0.1:7778/`

`10.0.2.2` remains in the narrow network allowlists for future emulator-host
development, but changing active clients at runtime is deferred to Phase 10.

> **Prototype security warning:** Android loopback is shared across apps. The
> embedded HTTP and WebSocket endpoints are not authenticated yet, including
> `/shell/exec`. Do not install this prototype alongside untrusted apps or treat
> it as production-ready. Authenticated transport and server identity checks are
> required before release.

## Building

The Android SDK and generated runtime binaries are not committed.

### Direct-native runtime setup

The local AVD uses a native x86_64 runtime; an ARM64 phone uses an ARM64
runtime. There is no QEMU compatibility runtime. The generated APK contains
only the ABI and Alpine rootfs selected for that build.

For local x86_64 AVD development, run from the repository root:

```bash
make install
make runtime RUNTIME_ARCH=x86_64
make run
```

For a physical ARM64 phone with USB debugging enabled:

```bash
make run-phone-test DEVICE_ID=<serial>  # optional when exactly one is connected
```

Switching from phone back to the AVD requires rebuilding the x86_64 runtime.
`make run` validates all four selected x86_64 PRoot files before Gradle or
emulator startup; it does not silently run the expensive runtime build.


### Direct Gradle APK-only setup

If only JVM tests or APK assembly are needed, configure an existing Android SDK
with `ANDROID_HOME` or `local.properties` and install the compile packages
directly. This path does not install the emulator, system image, or AVD, and an
APK built without generated runtime artifacts cannot start the embedded runtime.

```bash
export ANDROID_HOME=$HOME/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

A fresh checkout contains the tracked
`app/src/main/assets/linux/seed_version.json`, but no generated rootfs or native
bundle. The generated, Git-ignored selected ABI directory (`x86_64` or
`arm64-v8a`) contains `libproot.so`, `libproot-loader.so`, `libtalloc.so`, and
`libandroid-shmem.so`; no QEMU closure is retained.
The generated matching source asset is
`app/src/main/assets/linux/rootfs.tar.gz`. During the Android build, AGP expands
that gzip to merged `assets/linux/rootfs.tar`, which is stored with `noCompress`
so `AssetManager.openFd` can stream it; the packaged runtime rootfs is not gzip
compressed.

Android 10+ applies W^X to apps targeting API 29+: a file copied into writable
app home cannot be executed, regardless of `chmod` mode. The app therefore
uses AGP legacy JNI packaging. PackageManager extracts all four native files
into the installed app's executable native-library directory. `RuntimeService`
resolves the complete installation, sets `PROOT_LOADER` to its packaged loader,
and sets `LD_LIBRARY_PATH` to the same directory. The Termux Android-native
The packaged PRoot build is compiled for the selected Android ABI and must be used instead of a generic host binary.
under the Zygote application seccomp policy even though it works under an
interactive `run-as` process. Writable first-launch
extraction handles only rootfs data and the version marker under `filesDir`.

Before Gradle or emulator startup, `make run` checks all four selected paths
under `app/src/main/jniLibs/<emulator-abi>`; a missing, invalid, or
architecture-mismatched file fails immediately and prints the matching explicit
`make runtime RUNTIME_ARCH=...` command. JVM tests, APK assembly, lint, and
Compose-test compilation do not require a device. See
[`../docs/build-runtime.md`](../docs/build-runtime.md) for publication safety,
prerequisites, architecture switching, and marker versioning.

## Startup lifecycle

1. `BootController` checks the bundled marker, extracts the rootfs assets into
   `filesDir/linux`, and serializes extraction across activity recreation.
2. `MainActivity` starts and binds `RuntimeService` only after extraction is
   ready.
3. `RuntimeSupervisor` starts the installed native-library proot and polls
   `/health` through the service.
4. The startup screen blocks navigation while health is unknown/polling and
   offers Retry after failure.
5. `SeedNav` appears only after a healthy backend response.

Backgrounding the activity leaves the foreground service running. Destroying
the activity releases only its binding; force-stopping the app terminates the
service and runtime.

## Status

| Phase | Capability | Status |
|---|---|---|
| 5 | Four-screen Compose shell | ✅ complete |
| 6 | Android ↔ backend clients | ✅ complete |
| 7 | Native proot packaging and rootfs extraction | ✅ complete; native ARM64 device/AVD lane |
| 8 | Foreground runtime service | ✅ complete; managed PRoot/Uvicorn health verified |
| 9 | Startup, health gate, retry, loopback defaults | ✅ complete; app-domain instrumentation passed |
| 10 | End-to-end polish and runtime controls | ⬜ next |

## Versioning

- AGP 8.5.0
- Kotlin 1.9.24
- Compose BOM 2024.06.00
- compileSdk / targetSdk 34
- minSdk 26
- JVM 17
