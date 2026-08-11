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

### Fresh x86_64 emulator setup (recommended)

Run the complete setup from the repository root. It requires JDK 17 and Linux
KVM access through `/dev/kvm`; install Docker with buildx plus the runtime host
tools listed in [`../docs/build-runtime.md`](../docs/build-runtime.md).

```bash
# Installs the Android command-line tools, emulator, system image, and seed_dev AVD.
make install

# Generates the x86_64 proot and matching rootfs bundle.
make runtime RUNTIME_ARCH=x86_64

# Preflights the runtime, builds the APK, starts the AVD, installs, and launches.
make run
```

Keep that order on a fresh checkout: `make install`, then the explicit x86_64
runtime build, then `make run`. `make install` fails if `/dev/kvm` is unavailable
because the development emulator requires hardware virtualization. `make run`
never starts the large runtime build automatically.

For an arm64 physical device, generate and build that architecture explicitly:

```bash
make runtime RUNTIME_ARCH=arm64
make build
```

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
`app/src/main/assets/linux/seed_version.json`, but no generated rootfs or proot.
The generated, Git-ignored proot is placed at
`app/src/main/jniLibs/arm64-v8a/libproot.so` or
`app/src/main/jniLibs/x86_64/libproot.so`; only the selected ABI remains after
a successful architecture switch. The generated matching source asset is
`app/src/main/assets/linux/rootfs.tar.gz`. During the Android build, AGP expands
that gzip to merged `assets/linux/rootfs.tar`, which is stored with `noCompress`
so `AssetManager.openFd` can stream it; the packaged runtime rootfs is not gzip
compressed.

Android 10+ applies W^X to apps targeting API 29+: a file copied into writable
app home cannot be executed, regardless of `chmod` mode. The app therefore
uses AGP legacy JNI packaging. PackageManager extracts `libproot.so` into the
installed app's read-only/executable native-library directory, and
`RuntimeService` resolves `applicationInfo.nativeLibraryDir/libproot.so`.
Writable first-launch
extraction handles only rootfs data and the version marker under `filesDir`.

Before Gradle or emulator startup, `make run` checks the exact selected path
`app/src/main/jniLibs/<emulator-abi>/libproot.so`; a missing, invalid, or
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
| 7 | Native proot packaging and rootfs extraction | ✅ implementation complete; emulator acceptance pending |
| 8 | Foreground runtime service | ✅ implementation complete; emulator acceptance pending |
| 9 | Startup, health gate, retry, loopback defaults | ✅ implementation complete; instrumentation not run |
| 10 | End-to-end polish and runtime controls | ⬜ next |

## Versioning

- AGP 8.5.0
- Kotlin 1.9.24
- Compose BOM 2024.06.00
- compileSdk / targetSdk 34
- minSdk 26
- JVM 17
