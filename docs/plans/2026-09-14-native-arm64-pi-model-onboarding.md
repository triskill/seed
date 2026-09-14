# Native ARM64 runtime and Pi-backed model onboarding

**Status:** superseded for the first-run login flow
**Branch:** `feat/model-selection-improvoment`

> **2026-09-15 update:** Native ARM64 runtime work remains in effect, but the
> proposed first-run onboarding flow was removed. The app starts with packaged
> Pi defaults; provider key/login and model selection live only in Settings.
**Date:** 2026-09-14

## Goal

Recover the embedded orchestrator by removing the Android ARM64-host → QEMU
user-mode → x86_64 Alpine guest path, then replace the free-form Settings
provider/model/key form with a first-run, Pi-backed provider login and model
selection experience.

The target is one **native ARM64 Android runtime**: ARM64 Android device,
ARM64 Termux PRoot, ARM64 Alpine rootfs, ARM64 Node, and the bundled Pi CLI.
There is no x86 guest and no QEMU user-mode executable in the APK.

## Decisions

1. **Remove the entire x86 runtime option, not just its QEMU launcher.** The
   product APK will carry only `arm64-v8a` native runtime files and an ARM64
   rootfs. Consequently, the present x86_64 Android AVD cannot be the runtime
   test target. Development and release validation move to a physical ARM64
   device or an ARM64 AVD chosen and documented before this work lands.
2. **Do not embed or automate Pi's terminal `/login` and `/model` screens in
   Compose.** They are JavaScript TUI components in the bundled Node CLI, not
   an Android/Kotlin API or a stable machine protocol. Running them through the
   Shell would be a poor first-launch UI and would leave Pi's `auth.json` in
   the extracted guest filesystem.
3. **Reuse Pi's headless model RPC, not its TUI.** Pi 0.80.3 exposes
   `get_available_models`, `set_model`, and `set_thinking_level` RPC commands.
   It has no thinking-level discovery command; Seed derives the levels from
   each model's `reasoning`/`thinkingLevelMap` metadata. FastAPI/PiRunner
   remains their sole owner and exposes a protected local
   control-plane API to Compose. Compose owns presentation, filtering, errors,
   and persistence.
4. **MVP authentication is API-key based.** The Kotlin setup flow stores an
   API key in `EncryptedSharedPreferences`/Android Keystore and `RuntimeService`
   injects only the allowlisted provider environment variable as it does now.
   OAuth/subscription login is an explicit follow-up spike; it must not be
   presented as supported until its tokens can remain protected by Android
   storage throughout refresh and Pi use.

> “QEMU removal” here means the embedded Linux guest emulator. It does not
> mean deleting Android Emulator internals or Docker buildx/binfmt used by a
> developer host.

## Why Pi `/login` and `/model` cannot be directly reused in Kotlin

The installed Pi CLI confirms the split:

- `pi --list-models` is a command-line table, while `/model` is an interactive
  searchable TUI that refreshes Pi catalogs and persists a default provider
  and model.
- `/login` is an interactive provider selector. Its implementation opens a
  browser or displays a device code and persists credentials to Pi
  `auth.json` (mode 0600) under `PI_CODING_AGENT_DIR`.
- The current app deliberately does **not** use that guest file for secrets:
  `AndroidSettingsRepo` puts the API key in encrypted Android storage and
  `PiRuntimeEnvironment.toPiRuntimeEnvironment()` injects an allowlisted env
  variable on every runtime process generation.

Therefore direct reuse is **not possible as a Kotlin UI integration**. Pi
0.80.3 does, however, expose headless RPC equivalents for `/model`:
`get_available_models`, `set_model`, and `set_thinking_level`. It has no
thinking-level discovery RPC, so Seed derives levels from the returned model
metadata. They are reused through a backend-owned control plane. Pi documents
no comparable login/auth RPC, so `/login` remains non-reusable.
We must not scrape ANSI TUI output or parse the human table from
`pi --list-models`.

## Work order

Do the native-runtime recovery first. Do not build onboarding against a QEMU
runtime whose Node/Pi process is known to be unreliable.

---

## Phase 0 — establish the ARM64-only test lane

1. Choose and document the supported development target: the available
   physical ARM64 phone is the required acceptance device; optionally add a
   named ARM64 AVD if it is practical on supported developer hosts.
2. Change the Makefile/default setup instructions so `make run` does not
   promise a working x86_64 runtime. Either make it ARM64-only or fail early
   with a clear physical-device/ARM64-AVD instruction. Add Gradle ABI filtering
   for `arm64-v8a` so the runtime cannot be installed onto an x86_64 emulator
   and fail only later during startup.
3. Record baseline native measurements on that device: extraction duration,
   backend/Flask readiness, real `pi --mode rpc` startup, a provider-backed
   turn, and a terminal external command. These are the recovery baseline for
   the remaining phases.

**Exit:** the team has one reproducible ARM64 install/run command and a device
for instrumentation tests.

## Phase 1 — delete QEMU and all x86 runtime generation

### Delete build/package paths

1. Delete `scripts/build-qemu-x86-runtime.sh` and
   `scripts/package-qemu-x86.sh`.
2. Make `scripts/runtime-target.sh` ARM64-only: remove the x86_64 Termux,
   Alpine, Docker, ABI, checksum, and path configuration. Simplify
   `scripts/build-runtime.sh` to one target; remove opposite-ABI publication
   logic and explicitly clean stale `jniLibs/x86_64` and every QEMU closure
   library from `jniLibs/arm64-v8a` before publishing.
3. Remove Makefile `runtime-qemu-x86`, `ensure-phone-qemu-x86-runtime`, and
   `run-phone-x86-test`; simplify `runtime`, `check-runtime-arch`, the default
   `SYSTEM_IMAGE`, and help messages to the ARM64 contract.
4. Remove the QEMU ignore entries from `.gitignore`, then delete the existing
   ignored generated QEMU/x86 files from the working tree. Rebuild with
   `RUNTIME_ARCH=arm64` so `rootfs.tar.gz` contains ARM aarch64 BusyBox and
   `seed_version.json` has a new build id and an explicit native-ARM64 runtime
   format/version marker.

### Delete Kotlin runtime branches

1. Delete `NativeProot.resolveQemuX86_64()` and its filename constant.
2. Change `ProotCommand.base()` and `ProotRunner` so no API accepts or emits
   `-q`; every launcher uses the same native-only PRoot command.
3. Remove `RootfsArchitecture`, x86 parsing/defaulting, and
   `requiresQemuX86_64()` from `RootfsVersion`. Update `BootController` to
   write the new native-only marker. The new marker/build id must force
   re-extraction over a previously installed x86/QEMU `.version`.
4. Simplify `RuntimeService` and `SeedTerminalManager`: no architecture probe,
   no QEMU resolution, no `QEMU_CPU`, and no QEMU-only `NODE_OPTIONS=--jitless`.
   Native Node must retain V8 JIT.
5. Re-baseline `FLASK_STARTUP_TIMEOUT_SECONDS` in
   `backend/seed_backend/flask_manager.py`. The current 90-second setting was
   introduced solely for QEMU; choose a native-device value from Phase 0 and
   update its test/comment rather than retaining a workaround without reason.

### Tests and documentation

1. Replace QEMU assertions in `NativeProotTest`, `RootfsVersionTest`,
   `ProotRunnerTest`, and `NativeProotSmokeTest` with native-only marker and
   command tests (in particular, assert no `-q`). Remove the smoke-test
   JIT-less/QEMU branches while retaining guest Python, Pi RPC, Flask reload,
   and terminal checks.
2. Reduce `scripts/tests/runtime-tools-test.sh` to ARM64 fixtures and remove
   x86 target/mismatch cases. Add an APK inventory test that fails if the APK
   contains `libqemu-x86-64.so`, its prior dependency closure, or
   `lib/x86_64/` runtime files.
3. Update current user-facing `README.md`, `android/README.md`,
   `docs/build-runtime.md`, and `TODO.md` to remove QEMU commands and state
   the ARM64 device/AVD requirement. Preserve old plans as historical records
   rather than silently rewriting their history.

**Exit:** a clean generated APK has only the intended ARM64 PRoot bundle and
an ARM64 rootfs; an upgrade from the current x86 marker re-extracts; no runtime
code or command can select QEMU/x86.

## Phase 2 — prove the recovered native orchestrator

1. On the Phase-0 ARM64 target, install a clean APK and an upgrade over the
   prior QEMU APK. Verify `uname -m` is `aarch64`, `/health` is healthy, Flask
   reloads after an edit, the Terminal runs an external command, and the
   service handles stop/restart.
2. Run Pi in its production RPC mode with the current injected API-key path,
   then complete one real middleman → worker request. Capture only redacted
   diagnostics; never log environment values or credentials.
3. Make this connected native smoke a release gate. JVM tests and APK contents
   are necessary but cannot prove the Android app-domain PRoot/Pi interaction.

**Exit:** the original failure is resolved natively before product work begins.

---

## Phase 3 — add a Pi RPC model-control plane

1. Pin and test the **bundled** Pi version, not the host CLI. The rootfs
   currently installs `@earendil-works/pi-coding-agent@0.80.3`; make that pin a
   single build constant and run contract tests inside the generated ARM64
   rootfs. The host Pi is a different version and is not the APK contract.
2. Prove Pi 0.80.3's documented RPC commands and response schema in a fixture
   and in the native smoke lane:

   ```json
   {"id":"models-1","type":"get_available_models"}
   {"id":"model-1","type":"set_model","provider":"openai","modelId":"gpt-4o"}
   {"id":"thinking-set-1","type":"set_thinking_level","level":"low"}
   ```

   Cover no credential, invalid model, catalog/network timeout, cancellation,
   and the exact fields returned for provider, model ID/name, context/output,
   image, and thinking capability. Catalog availability is credential and
   network dependent; it is not a static promise that every Pi provider works.
3. Extend `PiRunner` with one serialized, ID-correlated request/response path
   while retaining the existing chat event reader. Unknown, duplicate, timed
   out, and cancelled IDs must not reach chat subscribers. Never let Android
   open or race Pi stdio directly.
4. Add a dedicated backend `PiControlService`/catalog Pi RPC process rather
   than issuing control traffic to the middleman or worker. Its only allowed
   operations are the model/thinking queries and validation/set commands; it
   gets the same selected credential environment but no editing tools or chat
   prompts. This preserves the two agents' long-lived RPC ownership and lets
   model discovery be cancelled without disturbing a turn.
5. Add versioned, loopback-only FastAPI endpoints for non-secret model metadata
   and selection validation. Before exposing them, add a per-runtime random
   bearer capability generated by `RuntimeService`, passed only to the backend
   process, and attached by the Android client. The present loopback API is
   unauthenticated; do not expose a control plane to WebView/other local
   callers without this boundary. Endpoints must never return an API key,
   authorization header, OAuth token, or raw Pi environment.
6. Define Kotlin `ModelOption`, `ProviderOption`, catalog loading/error state,
   capability fields, and selected `{provider, model, thinkingLevel}`. Preserve
   raw Pi IDs separately from friendly labels.

**Exit:** Compose can securely query the exact bundled Pi for its available
models/thinking levels and validate a choice via the backend-owned RPC process,
without parsing a terminal UI or human CLI table.

## Phase 4 — first-run provider connection and model selection

1. Add `OnboardingRepository` with an explicit incomplete/completed state. A
   fresh install displays setup instead of silently using the inconsistent UI
   default (`openai/gpt-4o`) or immediately starting the packaged
   `opencode-go/deepseek-v4-flash` agents.
2. Use this bootstrap sequence:
   - welcome/privacy explanation and a curated, allowlisted **provider** list;
   - API-key connection screen (or a no-key local-provider screen), which saves
     the key through `AndroidSettingsRepo` only;
   - extract/start a **control-only runtime** with that credential environment,
     but do not start the two agent roles yet;
   - call the protected Pi control endpoints, show a searchable provider-scoped
     model list and supported thinking levels, then validate the selected
     tuple with Pi;
   - review/save, mark onboarding complete, stop the control-only generation,
     and start the normal agent runtime with the selected values.

   The Phase-3 proof must confirm how a control-only Pi process starts before a
   final model has been picked. If Pi requires a bootstrap model, use a pinned,
   tested non-secret discovery default only for this process; it must never
   become the user's saved or silently active agent model.
3. Replace `SettingsForm.KNOWN_PROVIDERS` and the free-form model field with
   catalog-backed Compose selectors. Show model ID/name and only Pi-returned
   capability data; offer custom model entry only behind an explicit advanced
   path and validate it through `set_model`, not by trusting text input.
4. Extend `SettingsForm`, `SettingsRepo`, `AndroidSettingsRepo`, and
   `PiRuntimeEnvironment` to persist provider/model/thinking level. Store
   public selection data in DataStore and the API key only in
   `EncryptedSharedPreferences`; expand the credential-env allowlist only for
   provider/version combinations that the contract tests approve. Do not put a
   key in argv, a loopback request body, `config.json`, a model cache, or the
   guest's `auth.json`.
5. Treat selection as global to both middleman and worker for v0.1. After
   initial setup, a Settings change persists first and uses an explicit,
   idle-only apply action: stop/restart the PRoot generation, then verify Pi
   readiness. This avoids one runner changing model during a task and ensures
   startup `pi_cmd_for_role()` flags, runtime state, and stored state agree.
   Live `set_model` for both roles is a later feature only after in-flight-turn
   semantics are specified.
6. Update backend defaults/docs so their values are development fallbacks only.
   An incomplete onboarding or failed provider check must leave a clear
   setup-required/retry state, not a broken chat request.

**Exit:** a fresh user connects a supported BYOK provider, selects a model and
thinking level supplied by the exact Pi runtime, and completes a real
provider-backed RPC turn using the persisted choice after normal runtime start.

## Phase 5 — OAuth/subscription-login feasibility gate (not MVP)

Run this as a separate spike after the API-key flow works:

1. For every desired Pi OAuth provider, map the Pi login method (browser
   callback, manual callback URL, or device code), redirect URI/client
   registration, token refresh behavior, and model availability.
2. Decide one supported architecture before writing UI:
   - upstream/maintain a Pi JSON auth adapter that reports URL/device-code and
     completion events; Kotlin opens the browser through Custom Tabs and
     presents the code, **or**
   - use a provider's supported Android OAuth SDK/AppAuth flow and an explicit,
     reviewed method for Pi to consume/refresh its credential.
3. Demonstrate that access and refresh tokens remain in Keystore-backed Android
   storage. If Pi requires file-only `auth.json`, do not copy it permanently
   into the extracted rootfs. Either supply a reviewed ephemeral credential
   bridge or reject OAuth for this release. A mode-600 guest file is not an
   equivalent to the existing Android encryption contract.
4. Add cancellation, expiry/refresh, revocation, network-loss, provider error,
   and switching-account tests. Confirm only authentication state—not tokens—
   is exposed to Compose/logcat/backend diagnostics.

**Exit:** OAuth appears in the product only after its token lifecycle and Pi
integration pass the security review. Until then, “Connect” means API-key
onboarding, which is already compatible with Pi and the current runtime design.

## Acceptance checklist

- [ ] APK contains no QEMU executable, QEMU dependency closure, or x86 runtime
      libraries/rootfs.
- [ ] New app and upgrade from a QEMU/x86 install extract the ARM64 runtime and
      run Pi RPC with native V8.
- [ ] A real ARM64 device completes backend, Flask reload, terminal, and
      provider-backed orchestrator smoke tests.
- [ ] Fresh install displays setup and does not start the default agent before
      a user completes it.
- [ ] The model list comes from the exact packaged Pi RPC runtime, is
      non-secret at the Android boundary, searchable, provider-scoped, and
      persists a Pi-validated tuple.
- [ ] API keys stay in Android encrypted storage and reach Pi only by the
      existing allowlisted process environment on a runtime generation.
- [ ] `/login`/`/model` are not screen-scraped or embedded as a terminal UI;
      OAuth remains gated pending its dedicated secure adapter.
