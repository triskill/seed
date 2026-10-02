# Android device capabilities: first-slice verification

## Implementation

One injected `seed.android.call({method, params})` Promise interface, with generic
browser transport and a strict native registry for `capabilities.list`,
`camera.capture`, `sensor.list`, and `sensor.read`. Native camera/sensor operations
require explicit consent. No arbitrary reflection, `JavascriptInterface`,
wildcard origin grants, raw intents or filesystem/control-plane APIs.

Bridge installation requires document-start scripts and origin-bound WebMessage
listeners; unsupported WebViews fail closed. Native calls check source/current
page origin (including port) and main frame. Direct subframes are rejected;
same-origin frames are not a browser isolation boundary against their parent.
Requests are capped at 8KiB, one operation is active, and waits are bounded.
Navigation/disposal cancels work. Late camera activity results cannot satisfy a
later request. Sensor listeners unregister on completion/cancellation/timeout.

Camera captures are bounded JPEG previews (<=512px largest dimension, <=256KiB
JPEG), not full-resolution images or video. Sensors use one generic SensorManager
reader, including one-shot trigger sensors; streaming and GPS are not included.
Images are not saved automatically and returned data contains no native paths.

Both role system prompts document the same contract and limitations. Gradle
bundles only worker/middleman instructions into APK assets. Runtime startup
atomically replaces those two installed prompts, never the generated app/auth
store/rootfs image. Assets are validated before replacement and symlinked prompt
directories are refused. Two role files are independently atomic, not a
cross-file transaction. Live agents read the new instructions on startup.

## Local verification

- **303 JVM tests, zero failures/errors**: protocol schemas/origins/registry,
  camera slot generation/cancellation, prompt installation preservation, plus
  existing runtime/settings regressions.
- **12 Node SDK tests**: generic method forwarding without SDK updates, native
  unknown/invalid-method errors, UTF-8 size bounds, correlation, timeout,
  cancellation, helper preservation and cached-page restoration.
- **296 backend tests passed** with the existing virtualenv and webapp on
  PYTHONPATH. One existing Starlette/httpx deprecation warning.
- Debug lint, debug APK and instrumentation APK builds passed.
- Review identified cached-page restoration leaving SDK permanently disposed;
  a regression failed before the pageshow fix and passed afterward. No other
  actionable blocker/high/medium issue was identified by review. Error-code
  metadata drift and per-method JS schema duplication were corrected separately.

Commands:

```sh
node --test android/app/src/test/js/device-sdk.test.cjs
(cd backend && PYTHONPATH=.:../webapp .venv/bin/python -m pytest -q)
(cd android && ./gradlew :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleDebugAndroidTest --console=plain)
```

## Moto G32 Android13/API33 verification

Both APKs installed with `adb install -r` (no data clear).

- New capability classes: **OK (10 tests), 4.182 seconds**.
- Full connected suite on final APK: **OK (21 tests), 131.238 seconds**.
- Isolated loopback WebView fixtures confirmed SDK/native results, sanitized
  exceptions, exact-origin/port/subframe denial, malformed/duplicate/BUSY
  requests, and navigation cancellation. No generated-app files/storage loaded
  by those fixtures.
- Native tests confirmed bounded image encoding, fake-camera cancellation/late
  completion, consent/disposal cleanup, and a **real accelerometer host read**
  that first suspended for consent and then returned a measurement. A foreground
  test Activity was used; camera photo-taking was never automated.
- MainActivity reopened; runtime `/health` returned healthy with Flask up.
- Installed worker/middleman prompt SHA-256 hashes matched repository source.
- Production settings and generated-app file hashes (excluding Python bytecode)
  matched before installation and after the final tests/startup.
- Temporary ADB port forward removed. Pre-existing generated runtime marker
  `assets/linux/seed_version.json` was not modified by this feature.

Logs retained locally under `/tmp/seed-device-*`; no credentials or camera
images were printed. These are developer artifacts, not shipped app content.

## Pending acceptance / non-goals

**Actual camera capture is not yet device-accepted.** User-operated consent,
system camera launch, taking a photo, returning a preview, and canceling must be
checked using the production Compose launcher. Mocked bitmap/slot tests are not
proof of that flow. A generated-app feature using the API is also not yet tested;
the user's habit app was intentionally not modified.

Browser-local habit contents were not inspected during this update. Existing
manual persistence acceptance remains historical; settings/source hashes are
not a browser-store comparison. x86_64, full-resolution/video, GPS, stream
subscriptions, and broader Activity recreation/background acceptance remain
separate work. This bridge does not sandbox hostile same-UID Linux code.
