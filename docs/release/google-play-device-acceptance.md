# Signed-release device acceptance sheet (owner/operator)

**Not yet executed.** Use only after all policy, lifecycle, privacy, licensing and
signing gates close. Test the **Play-delivered** `cz.trety.seed` build on a
consenting device with disposable credentials and data. Do not uninstall or clear
`com.seed.app`; it is a different package. Never commit screenshots showing keys,
chat content, photos, coordinates or tester identities.

## Record before testing (private release record)

- Date, operator, Android build/API, device model, ABI and serial kept privately.
- Play track, uploaded signed AAB SHA-256, `versionCode`, installed package version,
  Play App Signing certificate fingerprint and any available Play delivery size.
- `adb -s DEVICE_ID shell getconf PAGE_SIZE`: write down **4096** or **16384**.
  A 4096-byte result does **not** prove 16 KB support. Confirm the device uses
  `arm64-v8a`. Keep evidence without device serial or personal data in public docs.
- Baseline hashes of *disposable* generated-app and settings files, if you have a
  safe way to capture them. Do not export live keys or old-package data.

## Test matrix — mark pass/fail/not run and attach a short symptom

| Check | 4 KB Android 15/16 | 16 KB Android 15/16 | Notes/evidence |
| --- | --- | --- | --- |
| Play installs signed `cz.trety.seed` on an empty test profile; old app untouched |  |  |  |
| Extraction starts bundled PRoot, Python backend, Flask and Pi; no startup crash |  |  |  |
| Provider setup with **test key**; chat produces a response and generates/edits local app |  |  |  |
| App WebView shows generated app; terminal opens/executes; rotation and process recreation |  |  |  |
| Camera/location/sensors: deny, allow once, remembered allow/revoke; no unexpected data upload |  |  |  |
| Offline/provider errors/retry show honest state; no secret in logcat or shared report |  |  |  |
| Foreground notification Stop, Android `dataSync` timeout and explicit Start/recovery |  |  | **BLOCKED: not implemented** |
| Restart and Restore preserve documented generated app/settings/browser boundaries |  |  | Use disposable data only |
| Play update from earlier signed test code to higher `versionCode` preserves expected data |  |  | Never use debug install as upgrade proof |
| Play pre-launch report/device coverage/reviewer access and report-delivery flow checked |  |  | **BLOCKED until owner setup** |

On a 16 KB system, observe actual guest process startup, tool use, terminal PTY
and lifecycle; ELF/ZIP alignment checks alone do not establish runtime support.
Stop on a data-loss, credential-exposure, native crash or orphaned-runtime result.
Record which test failed, exact build fingerprint, OS/page-size and redacted logs;
do not mark a failed or skipped test as passed. Rebuild, re-sign, re-upload and
repeat the affected checks after any code, runtime, manifest or build change.

**No device tests, signed artifact, key, Console access or Play upload were used
to create this template.** The previous local unsigned AAB and debug tests are
not substitutes for this matrix.
