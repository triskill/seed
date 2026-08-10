# Seed Android app

The Android shell contains four Compose screens (App, Chat, Shell, Settings),
extracts the bundled Alpine runtime on first launch, starts it through a
foreground service, and waits for the loopback backend before showing the main
navigation.

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

The Android SDK and generated runtime binaries are not committed. Configure the
SDK with `ANDROID_HOME` or `local.properties`:

```bash
export ANDROID_HOME=$HOME/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"

cd android
./gradlew :app:testDebugUnitTest :app:assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

A fresh checkout contains only `assets/linux/seed_version.json`. Generate the
arm64 proot binary and Alpine rootfs before an embedded-runtime build or device
test:

```bash
./scripts/build-runtime.sh
```

See [`../docs/build-runtime.md`](../docs/build-runtime.md) for prerequisites and
artifact verification. The generated proot is arm64, so end-to-end runtime
verification requires an arm64 device/emulator. JVM tests, APK assembly, lint,
and Compose-test compilation do not require a device.

## Startup lifecycle

1. `BootController` checks/extracts `filesDir/linux` and serializes extraction
   across activity recreation.
2. `MainActivity` starts and binds `RuntimeService` only after extraction is
   ready.
3. `RuntimeSupervisor` starts proot and polls `/health` through the service.
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
| 7 | Runtime assets and extraction | ✅ complete |
| 8 | Foreground runtime service | ✅ complete |
| 9 | Startup, health gate, retry, loopback defaults | ✅ complete |
| 10 | End-to-end polish and runtime controls | ⬜ next |

## Versioning

- AGP 8.5.0
- Kotlin 1.9.24
- Compose BOM 2024.06.00
- compileSdk / targetSdk 34
- minSdk 26
- JVM 17
