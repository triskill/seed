# Device Priority Fixes Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Fix repeated native consent, preserve generated-app navigation on rotation, and add foreground location/GPS through the existing JSON API, in that order with separate commits.

**Architecture:** Keep the exact-origin WebMessage boundary and explicit registry. Persist native consent separately from provider settings, restore WebView browsing state rather than always loading the index, and add an Android-permission-aware cancellable location adapter. No app storage reset or generated-app edits.

**Tech Stack:** Kotlin/Compose, SharedPreferences for small origin-bound grants, WebView saved state, Android LocationManager / ActivityResult permissions; existing JSON SDK and agent prompts.

## Confirmed causes and decisions

1. AndroidDeviceCapabilities.consent unconditionally suspends for every invocation and stores no grants. User approves buttons **Allow once / Allow / Deny**: Allow persists origin+capability grant across sub-app pages until revoked in Settings. Native permissions remain separate and cannot be bypassed. Treat sensor.list/read as one Sensors grant; camera.capture is Camera. Grants must survive host/recreation/restart, not be global or per-URL-path.
2. AppScreen remembers a WebView only in the current composition, and factory calls loadUrl(index) unconditionally. Activity rotation recreates it, with no explicit saveState/restoreState path; loading index defeats restoration. MainActivity also briefly gates SeedNav behind binding readiness, so saved state must not be consumed/lost before AppScreen returns.
3. GPS/location was explicitly deferred; registry, manifest and native host have no location implementation. Add location.current with foreground Android coarse/fine permission and bounded one-shot fix; no background tracking.

## Task 1: Remembered consent and revocation

Create device consent store/policy and tests. Canonical exact origin including port, groups Camera/Sensors; explicit injectable store for tests. Android storage uses a dedicated preferences file, never provider/credential prefs. Allow once doesn't persist; Allow persists only an active user's approval, before operation proceeds. Deny/dismiss/cancel doesn't grant. Re-read store on every operation so revocation takes effect. Unknown methods can't create grants. Revocation does not cancel data already delivered.

Modify AndroidDeviceCapabilities/rememberDeviceCapabilityHost and device_strings.xml for three buttons. Add a Device access section to Settings with origin and per-capability Revoke controls; no backend calls/provider saves from revocation. Existing instrumentation must inject isolated grants, never production prefs. Tests first: once vs remembered, recreation/storage persistence, camera/sensor/origin isolation, deny/cancellation, revocation and repeated calls without prompts. Verify native UI labels and revocation UI as well as logic.

Run focused JVM and native tests, full JVM/lint/APK builds, connected regressions if phone available, review, record results in TODO/report/prompts, commit excluding seed_version.json. Only then implement task 2.

## Task 2: Rotation/navigation restoration

Reproduce with isolated loopback pages and Activity recreation (real rotation if phone available). Test current route/history retained before changing production code. Save/restore a WebView Bundle via a lifecycle-safe saveable holder that survives delayed runtime binding and navigation composition. Restore before loading; load configured index only if no valid state. Validate restored URLs with navigation policy; reject untrusted restored state. Close/cancel native bridge before WebView disposal; do not persist open device dialogs/jobs. Do not claim arbitrary JS heap survives activity/process death. Original implementation preferred no manifest configChanges workaround. Superseding approved live-rotation design: MainActivity and the isolated debug fixture handle `orientation|screenSize|screenLayout`, keeping the same Activity/WebView/device host while Compose and AndroidView resize. Android documents screenLayout alongside orientation/screenSize; smallestScreenSize concerns physical display/multi-window changes and is deliberately not opted out here. Retain saved browsing restoration for genuine recreation/process death (not JS heap restoration), and retain background sensor cleanup. Add source-manifest red/green regression plus requestedOrientation portrait/landscape tests for identity, unsaved form/JS/scroll/no reload, stream continuity and background stop. Keep explicit recreate tests. Device execution/install and a verified separate commit belong to the parent; do not change app data or seed_version.json.

Test nested route, Back stack, fragment/query and fallback; verify in full MainActivity flow, not only directly rendered AppScreen. Run regression suite, document and commit separately. Preserve user localStorage and generated-app files.

## Task 3: Foreground location/GPS

Extend registry with location.current, params bounded timeoutMs (default15000, 1000..60000) and accuracy ('coarse' default or 'fine'). Return latitude/longitude/accuracyMeters/timestampMs, ageMs and coarse/fine status, no paths. Coarse requests must not leak fine coordinates even if Android grants fine access (quantize output and conservatively report accuracy). Add manifest coarse/fine declarations only, no background permission. User consent uses new origin-bound Location group. Android ActivityResult permission request handles fine+coarse together and approximate-only grants; request is not an automatic grant. Restricted/disabled/no provider -> structured error, denial/cancel/timeout -> cleanup. Choose suitable enabled provider under granted precision; never require precise permission to use approximate location. Accept only a fix at most 10 seconds old and expose ageMs; discard older fixes rather than presenting stale coordinates as current. Timeout removes listeners/cancels current-location operation. Existing approximate permission suffices even if fine was preferred; do not repeatedly prompt for an upgrade after the user chose approximate. Fine+coarse are requested together only when first seeking fine access. Camera/Android permission late-result safety applies here too.

Use injected provider/permission seams for red/green deterministic tests, device tests for denial/approximate behavior where safe; no position values in logs. Update both system prompts, metadata, docs and prompt tests, deploy role instructions through existing asset mechanism. Real location acquisition needs location services enabled and user approval, label unrun acceptance honestly. Verify and commit separately, keep generated runtime marker excluded throughout.
