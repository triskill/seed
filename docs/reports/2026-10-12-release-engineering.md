# Release engineering validation — 2026-10-12

Branch: `app-store-preparation`. This is local validation, not publication approval.

## Changes

- CI now runs `make verify-play-tools`, covering all release artifact, runtime
  inventory, fresh-build gate and Termux repack fixtures instead of only the three
  Termux tests. These standard-library tests do not need generated runtime assets.
- Corrected Android README setup to the existing API 36 / build-tools 35.0.0 /
  NDK 28.2.13676358 requirements and AGP 8.10.1 / Gradle 8.11.1. No build constants
  were changed. Corrected its obsolete unauthenticated-control claim: backend
  control/chat use the runtime bearer capability; generated Flask apps and
  same-UID agent execution are not isolated by that capability.

## Packaging audit

Read-only comparison of the 17 top-level backend Python modules against the
existing generated rootfs found only `orchestrator.py` differs. That file is
already delivered by the APK-owned backend patch installer, alongside
`pi_runner.py`. The other 16 files match byte-for-byte. No unpatched backend
module drift was found in this checkout. This is not a general dependency,
guest-image provenance or arbitrary generated-code audit.

The existing release workflow deliberately produces an unsigned AAB, version
0.1.0 / code 1. Signing custody and the next upload version remain owner decisions;
no key, signing configuration or version increment was invented.

## Verification

- Release tool fixtures: **48 passed** (25 artifact, 16 inventory, 4 fresh-build
  gate, 3 Termux repack).
- Both Bash native tooling/inventory suites: **passed**.
- CI workflow YAML parses locally; no remote CI run was launched.
- Full Android baseline: **382 debug + 382 release JVM tests**, zero failures,
  errors or skips. Debug/release lint passed with **42 / 44 warnings**. Debug and
  unsigned release APKs and AndroidTest APK compiled; instrumentation not run.
- Fresh `make release-check`: **passed**, actual Gradle `bundleRelease` build
  followed by the strict ARM64 AAB inspector. Actual ARM64 unsigned release APK
  inspector also **passed**; both required APK runtime assets are ZIP_STORED.
- `jarsigner -verify` confirms the AAB is **unsigned**. No new bundletool delivery
  measurement or device/16 KB runtime acceptance was performed.
- Foreground timeout/user-stop implementation was deferred and remains an open
  engineering gate; this build does not claim that lifecycle work is complete.

Logs: `/tmp/seed-release-tools-20261012.log`,
`/tmp/seed-apk-inventory-20261012.log`, `/tmp/seed-runtime-tools-20261012.log`,
`/tmp/seed-android-baseline-20261012.log`.
Packaging comparison: `/tmp/seed-backend-packaging-audit-20261012.json`.


## Artifact fingerprints

| Artifact | Bytes | SHA-256 |
| --- | ---: | --- |
| `android/app/build/outputs/bundle/release/app-release.aab` | 147578749 | `787f9379b61850f1e3e8934a7b0f3ae88ae3ab38e5d89d635253cd73cbeb49eb` |
| `android/app/build/outputs/apk/release/app-release-unsigned.apk` | 403940527 | `89a612fe9a6940dadf26c8c581795de0a6849b734c33ad8716038bda9988dbe5` |

These are raw archive sizes, not Play delivery or installed sizes. These final
fingerprints supersede the older artifact hashes for this local build only.
Final logs: `/tmp/seed-release-check-20261012.log`,
`/tmp/seed-apk-check-20261012.json`, `/tmp/seed-aab-check-20261012.json`,
`/tmp/seed-aab-signature-20261012.log`.

No rootfs regeneration, device operations, new keys, signing, uploads or account
changes were performed. The pre-existing `seed_version.json` modification is
preserved. Runtime/policy/legal/privacy and signed-device release gates remain
separate from a passing local artifact check.
