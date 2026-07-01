# Seed Android app

Phase 5 — the Android shell. 4 screens (App, Chat, Shell,
Settings) with bottom navigation; a WebView in the App
tab that loads the dev backend at `http://10.0.2.2:7778/`.

## Building

The Android SDK is **not** in the repo — every developer
points Gradle at their own install via `local.properties`
or the `ANDROID_HOME` env var. The repo's `local.properties`
is committed as a sample for CI; on a fresh checkout the
file is missing and `./gradlew assembleDebug` will fail
with `SDK location not found`.

To set up a local install:

```bash
# 1. Install JDK 17 or newer (Ubuntu: `sudo apt install openjdk-17-jdk`)
java -version   # must show 17+

# 2. Download the command-line tools to ~/android-sdk:
#    https://developer.android.com/studio#command-line-tools-only
#    (the .zip URL is on the same page; pick the Linux one)

# 3. Install the SDK platform + build-tools (one-time, ~500 MB):
export ANDROID_HOME=$HOME/android-sdk
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager \
    "platform-tools" "platforms;android-34" "build-tools;34.0.0"

# 4. Either set ANDROID_HOME (preferred — the project
#    .gitignore covers it; no `local.properties` needed)
#    or create one:
export ANDROID_HOME=$HOME/android-sdk
#   …or: echo "sdk.dir=$ANDROID_HOME" > local.properties

# 5. Build:
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk
```

## Tasks

| # | Task | Status |
|---|---|---|
| 5.1 | Gradle init, blank MainActivity | ✅ done |
| 5.2 | 4-section nav skeleton (App/Chat/Shell/Settings) | ⬜ not started |
| 5.3 | WebView in App screen | ⬜ not started |
| 5.4 | Chat screen UI (input + transcript) | ⬜ not started |
| 5.5 | Shell screen (PTY output) | ⬜ not started |
| 5.6 | Settings screen (provider, model, API key) | ⬜ not started |
| 5.7 | App state + persistence | ⬜ not started |
| 5.8 | Theme + colour scheme | ⬜ not started |
| 5.9 | Polish (icon, splash, accessibility) | ⬜ not started |

Tasks 5.1-5.2 are buildable without a device or emulator
(`./gradlew assembleDebug` produces an APK). Tasks 5.3+
need a device or emulator to verify the UI renders
correctly. We can use the Android emulator with KVM
acceleration (this dev machine has `/dev/kvm`).

## Module structure

Phase 5 ships a single-module project. Multi-module
refactor is a future task when the build time or code
size justifies the module boundaries.

## Versioning

- AGP 8.5.0
- Kotlin 1.9.24
- Compose BOM 2024.06.00 (Material 3)
- compileSdk = 34 (Android 14)
- minSdk = 26 (Android 8.0 Oreo)
- targetSdk = 34

These match the values in the Phase 5 plan. Bump them
together — Compose compiler, Material 3 API surface, and
the AndroidX BOM all move in lockstep.
