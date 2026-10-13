# Security and privacy release review — 2026-10-12

**Not a penetration test or Play approval.** Static review of Android/backend code,
existing privacy/policy drafts and the currently generated runtime. No backend or
Android production code changed by this reviewer. Intended agent freedom remains
unchanged. Root implemented backup exclusions separately during this batch.

## Release blockers and decisions

| Finding | Classification | Required action |
| --- | --- | --- |
| Agents/generated Python share the app UID and can read credentials, alter files and make network requests. PRoot and guest permissions are not a security boundary. | Accepted architecture; policy/product risk, not a newly discovered bypass | Explain experimental execution and credential exposure; owner must resolve distribution eligibility without claiming a sandbox or silently restricting agents. |
| Pi includes automatic native helper downloads, and shell/package installs remain possible. | Concrete conditional code path plus broader architectural policy risk | Resolve executable-code policy; see evidence below. A prompt prohibition is not enforcement. |
| Central AI conversation lacks an established in-app reporting/flagging flow and verified harmful-content prevention. | Product/policy release blocker | Implement reporting and response handling; assess generated apps as well as chat. External email alone does not establish the required in-app flow. |
| Privacy policy and Data Safety remain owner-review drafts, with identity/contact/provider retention/deletion and actual network flows unconfirmed. | Owner and implementation blocker | Publish accurate policy, expose it inside app, approve Console answers and verify release network behavior. Do not claim no collection or universal encryption. |
| Persistent foreground runtime declares dataSync; lifecycle timeout/explicit stop work remains pending. | Lifecycle engineering and service-type policy blocker | Implement bounded timeout shutdown and user-visible stop/restart behavior; validate Android 15/16 lifecycle and justify service type/Console declaration. Review occurred before the proposed lifecycle changes were implemented. |

## Backup and credentials

Before this batch, the manifest omitted backup restrictions. Pi credentials are
plain JSON in persistent `filesDir/linux/pi-agent/auth.json`; sessions and generated
files can also contain sensitive information. App-private permissions and backup
size quotas do not establish backup exclusion. Legacy encrypted preferences should
also be excluded because their Keystore keys do not restore with preferences.

Root added `android:allowBackup="false"`, `fullBackupContent` rules and Android-12+
`dataExtractionRules`. Reviewed XML excludes root/file/database/sharedpref/external
and device-protected root/file/database/sharedpref domains for cloud backup and
device transfer. This is a concrete privacy improvement, not at-rest encryption.
It deliberately removes automatic platform recovery/transfer. Validate merged
release manifest and supported devices; do not promise control over every OEM
migration tool, privileged access or backups made by older versions.

Backend `pi_settings.py` writes credential/config JSON using temporary files,
mode 0600 and atomic replacement; app-private directory creation uses mode 0700.
Configuration responses return provider IDs/selection rather than auth keys.
Control validation errors and Pi failures use generic responses. Selected provider
environment variables and runtime capability are stripped from ordinary child
environments; best-effort process visibility hardening is not a substitute for
same-UID isolation. File credentials remain readable to intended agent execution.
Provider login confirms local persistence, not remote authentication. Durable
Pi sessions can retain prompts/responses/tool context even where UI replay is limited.

Deletion/revocation UX, provider-side deletion and retention promises remain
unverified. Restore preserves the documented app/config boundary and is not a
secure erase, credential revocation or backup. Uninstall does not delete remote
provider data or earlier backups.

## Backend and WebView boundaries

- `service.py` requires a loopback peer plus the per-runtime bearer capability
  for control and shell endpoints, and for chat WebSocket admission. The no-token
  compatibility path is restricted to in-process TestClient peers, not real
  loopback callers. `/health` intentionally exposes limited readiness without auth.
- These controls resist unrelated loopback clients; they are not a sandbox against
  same-UID runtime code. Flask generated-app routes on port 7778 are unauthenticated.
  Loopback is reachable by other local apps, not a private per-app network namespace.
  Sensitive generated routes therefore need their own appropriate design.
- `FlaskManager` uses `--debug --no-debugger`: the development reloader is retained,
  but the interactive Werkzeug debugger is explicitly disabled. Do not report a
  currently enabled debugger/PIN bypass in this managed path. The developer-only
  `python -m seed_app.app` entrypoint still calls `app.run(debug=True)`; that is not
  the managed Android launch path. Generated code can change its own behavior.
- Android network security config permits cleartext for 127.0.0.1 and 10.0.2.2,
  with HTTPS-only defaults for participating Android clients. It does **not**
  enforce TLS on arbitrary guest Python/Node/native outbound requests.
- WebView disables file/content access and mixed content. Its host navigation
  allowlist is not a general outbound-network firewall: HTTPS subresources and
  generated code can transmit data. Do not describe it as preventing exfiltration.
- The device bridge uses an expected origin, main-frame checks, document generation
  ownership and lifecycle cancellation. Native consent/Android permission are
  separate from what generated code does after receiving a result.

## Permissions and user data

Manifest requests foreground coarse/fine location, Internet/network state,
notifications and dataSync foreground-service permissions. It does not declare
background location, CAMERA or shared-storage permission. Camera preview uses an
external camera intent; this still handles sensitive image data. Location is
one-shot foreground; sensor streams have page/lifecycle cancellation. Consent can
be remembered/revoked, but returned camera/location/sensor data may subsequently
be saved or sent by generated code. Final supported flows and disclosures need
release-device testing, including denied/revoked grants and approximate location.

Runtime stdout/stderr can reach Android logs; generic API failures do not prove
all runtime/generated logs are secret-free. No developer analytics collection was
established by this review. Bundled telemetry/OpenTelemetry packages alone do not
prove either enabled collection or absence of it. Observe the final release before
approving privacy claims.

## Exact native-download and catalog evidence

Inspected `android/app/src/main/assets/linux/rootfs.tar.gz` directly; bundled
`usr/local/lib/node_modules/@earendil-works/pi-coding-agent/package.json` identifies
**0.84.2**. Paths below are relative to that package. This is static source/archive
inspection, **not an observed native download or authenticated network trace**.

- `dist/utils/tools-manager.js:212-278` obtains latest GitHub releases, downloads
  archives, extracts native fd/rg binaries and marks them executable.
  `ensureTool` at lines 287-324 invokes this if no installed tool is found and
  `PI_OFFLINE` is not enabled.
- `dist/core/tools/grep.js:99,148` obtains then spawns rg;
  `dist/core/tools/find.js:161` obtains fd. Interactive startup invokes both in
  `dist/modes/interactive/interactive-mode.js:728-729`.
- No executable named rg, fd or fdfind was found in the inspected rootfs. A cache
  directory named fd is not an installed binary. The Android platform special-case
  is not protection for guest Linux Node. Successful execution of downloaded GNU
  ARM binaries under Alpine was not demonstrated; this does not remove the
  conditional native-download attempt.
- `pi update --models` is a **different path**: `dist/package-manager-cli.js:350-376`
  calls `ModelRuntime.refresh`; `dist/core/remote-catalog-provider.js` fetches JSON
  from `https://pi.dev/api/models/providers/<provider>` and persists metadata.
  No Codex app-server/native downloader was found in the inspected bundled
  Earendil JS modules. Do not label this version's model-only update a substantiated
  native-download path or extrapolate the finding to future/custom configurations.
- Catalog requests to pi.dev are another third-party network endpoint relevant
  to privacy disclosure, distinct from configured AI inference providers.

A possible narrow engineering change is to bundle pinned Alpine rg/fd and remove
Pi's automatic missing-tool download fallback. That preserves ordinary shell
freedom but does not close arbitrary native install/self-update policy issues.
It requires a pinned Pi patch, build/regression checks and a rebuilt rootfs;
source-only backend changes do not update an existing phone bundle. Setting
`PI_OFFLINE` globally is not equivalent: it also changes catalog/network behavior.
No such runtime change was made or represented as necessary policy clearance.

## Validation and scope

Existing tests cover managed Flask debugger disabling, capability checks and
remote-peer rejection, environment scrubbing, credential persistence/response
redaction, bounded model-update process ownership, and replay privacy. A focused
suite passed: **56 tests**, one existing Starlette/httpx deprecation warning, in
5.12 seconds. Command:

```sh
.venv/bin/python -m pytest backend/tests/test_flask_manager.py backend/tests/test_pi_control.py backend/tests/test_process_env.py backend/tests/test_shared_pi_config.py backend/tests/test_model_catalog.py backend/tests/test_replay_privacy_regression.py -q
```

Android backup validation
and full project baseline are owned by root and are not independently certified
here. No provider traffic, live credentials, device mutations, Google submission
or external account actions were used.
