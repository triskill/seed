# Device priority fixes: verification

## 1. Remembered native consent

Root cause: every AndroidDeviceCapabilities invocation unconditionally waited for
consent and stored no grant. Implemented exact **Allow once / Allow / Deny** UI.
Allow persists a canonical origin+group grant in a dedicated preferences file;
Sensors covers list/read, Camera remains separate. All sub-app paths share the
origin grant, other origins/ports do not. Settings > Device access revokes groups
without touching provider configuration. Each new operation rechecks grants.
Android permissions remain independently enforced. Canceled/stale prompt responses
cannot create grants; native tests inject isolated stores.

Local verification: **307 JVM tests, zero failures/errors**, debug lint, debug APK
and instrumentation APK build passed. Prompt tests: **12/12**. Behavioral consent
and metadata/prompt regressions observed RED before GREEN. Review found no blocking
or important issue; disk persistence across an actual process restart is not yet
accepted (same-process store recreation is covered).

New native/Compose UI tests compile but have not run. The phone is attached but
ADB is **unauthorized**; no install/data clear/device mutation performed for this
fix. Previous 21-method device acceptance belongs to the earlier APK, not this
fix. The expanded suite currently contains 23 methods. Generated app/browser/
provider data and the pre-existing seed_version.json change are untouched.

## 2. Rotation

### Current live-orientation behavior (supersedes the original limitation below)

MainActivity and the isolated debug fixture now handle
`orientation|screenSize|screenLayout` without Activity recreation. Existing
remember keys are stable across rotation; the same WebView/device host remains
live, retaining DOM, JavaScript heap, form, scroll and active sensor streams,
while Compose/AndroidView resize. Background sensor cleanup is unchanged.
Saved browsing restoration and all four explicit recreation/disposal tests remain
for genuine recreation/process death; arbitrary heap restoration is not promised.
`smallestScreenSize` is not included: this change targets rotation, not physical
display or multi-window continuity. Android references:
https://developer.android.com/guide/topics/manifest/activity-element and
https://developer.android.com/guide/topics/resources/runtime-changes.

Verification: new manifest contract failed before the change (1 test, expected
missing configChanges), then full `./gradlew testDebugUnitTest lintDebug
assembleDebug assembleDebugAndroidTest` passed: **334 JVM tests, 0 failures/errors/skips**,
lint **0 errors / 36 warnings**, both debug APKs built. Two new instrumentation
methods compile: portrait/landscape identity + live JS/form/scroll/document-token
and resize assertions; real accelerometer continuity + background stop (skips on
sensorless devices). Both restore requestedOrientation in finally.
Installed both APKs with -r: **46/46 Moto G32 instrumentation tests passed**,
156.601 seconds, including live orientation and sensor/background tests. Backend
healthy after startup; temporary health ADB forward removed. Read-only review found
no blockers. Settings/auth hashes match the earlier baseline. Two generated-app
files (lm.html/lm.js) differ from the older recovery baseline; no pre-install app
snapshot was taken for this step, so unchanged app hashes are not claimed. No app
source was edited by this implementation, and no data was cleared or restored.
Manual rotation in the user's generated page remains acceptance work.
Backend/SDK unchanged, so their suites were not rerun.
Logs: /tmp/seed-live-rotation-device.log and /tmp/seed-live-rotation-build.log.
Pre-existing seed_version.json remains untouched.
User manually accepted real camera capture/return; cancel flow is not yet accepted.

### Historical recreation-only implementation

Root cause: a recreated AppScreen always loaded the configured index and had no
explicit browsing-state capture/restore. Implemented a saveable state holder
that captures the live WebView during Activity state saving (before disposal),
restores trusted route/history before any fallback load, and clears the live
Activity/view reference during disposal. A stable root navigation-state boundary
retains unconsumed child state while runtime binding delays composition, including
another recreation during that delay. Query/fragment and Back history are kept;
unsafe history falls back to a safe current URL or configured index. Native
requests/dialogs are canceled, not restored. No manifest configChanges workaround,
Activity leak or generated-app/localStorage reset.

**310 JVM tests**, lint and both APK builds passed. Three browsing-policy
regressions failed before implementation and passed afterward. Four isolated
production-AppScreen recreation/history/gating instrumentation tests compile but
are unrun; expanded connected suite currently contains 27 methods. Review found
no blocker/important actual bug. Phone remains ADB unauthorized. Actual rotation
and full MainActivity runtime acceptance are pending; no claim that arbitrary
JavaScript heap, DOM form state or process-death state survives.

## 3. Foreground location/GPS

Added `location.current` with optional accuracy coarse/fine (default coarse) and
integer timeoutMs 1000..60000 (default15000). Returns latitude, longitude,
accuracyMeters, timestampMs, precision and ageMs. Separate origin-bound Location
grant/revocation and explicit location consent text; Android coarse/fine grants
remain independently required. First fine request asks coarse+fine together;
approximate-only grants are respected without repeated upgrade prompts.

Coarse output is quantized to two decimal degrees and accuracy reported at least
1500m, even when Android has fine permission. Source providers use network/fused
or GPS when precise platform access permits; GPS-only fine-granted devices can
still provide a quantized coarse result. Only fixes aged 0..10000ms are accepted;
permission is rechecked before returning. No coordinates logged, no background
permission, Google dependency or tracking service. Production Activity lifecycle
blocks hidden starts and stops reads with structured CANCELLED errors; permission
activity waits remain isolated from camera and don't leak late results to another
request. Success, denial, stop, timeout and cancellation remove listeners.

Final verification: **323 JVM tests**, **12 browser SDK tests**, **298 backend
tests**, lint and both APK builds passed. One pre-existing Starlette/httpx warning.
Protocol/foreground/privacy and GPS-only/coarse regressions observed RED/GREEN.
Review found no additional important bug beyond the subsequently corrected
GPS-only provider fallback. Both agent prompts and API docs now describe location
and remembered consent; they are bundled for deployment on runtime startup.

**35 instrumentation methods compile but are unrun on these fixes.** Fake
permission/provider and UI tests cover location scenarios; actual Android
permission dialogs, live GPS, persisted consent across process restart and real
rotation remain pending because the phone is still ADB unauthorized. No install,
position acquisition or device data reset performed for these fixes.

## Next acceptance

Authorize USB debugging, install both APKs with -r, run the 35-method suite, then
check remembered consent/revocation, rotate a real generated sub-page in both
directions, and approve approximate/precise location on the phone. Validate
settings, app files and user-reported browser data without clearing storage.
Live sensor/camera/location requests are never accepted solely from fake tests.
