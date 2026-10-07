# Google Play Beta Preparation Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Prepare locally verifiable Google Play beta materials for `cz.trety.seed`, identify unresolved eligibility/release blockers, and leave publication/account/legal decisions to the owner.

**Architecture:** Keep the unrestricted embedded runtime architecture and native recovery unchanged. Add an unsigned AAB build and fail-closed, dependency-light artifact inspection; document current official Play requirements, declared dependencies, privacy/data flows, and owner actions. Preparation is not a claim of Play approval or a signed/publishable release.

**Tech Stack:** Android Gradle/Kotlin, Python standard-library artifact checks and unittest, Bash/Make, Markdown, official Google documentation.

## Safety and execution scope

- Branch `app-store-preparation`, starting at `8979b80`; use the current checkout to retain its generated runtime inputs. No phone operations, uploads, account changes, signing keys, or migrations.
- Preserve and exclude the pre-existing change to `android/app/src/main/assets/linux/seed_version.json`. Never regenerate/replace the runtime as part of preparation.
- Baseline: debug JVM task succeeds before preparation (existing 368-test result). Existing artifacts are generated locally, not committed.
- Preserve the current accepted agent freedom. Do not introduce isolation or claim interpreter exceptions guarantee Play eligibility.
- No invented license grants, contact details, privacy URLs, retention promises, provider contracts, account type, ratings, screenshots, or Play Console answers.

### Task 1: Evidence-backed eligibility audit

**Create:** `docs/release/google-play-readiness.md`.
1. Research official current target SDK, App Bundle/download limits, native 16 KB pages, executable-code policy, AI content, privacy/Data Safety, and account-dependent testing requirements.
2. Map each requirement to local files/artifacts. Distinguish published deadlines from uncertain future requirements and measured facts from interpretation.
3. Record blockers and owner-dependent questions. Link all primary sources and consultation date.
4. Commit the audit with this execution plan.

### Task 2: Artifact checker with red/green tests

**Create:** `scripts/check-play-artifact.py`, `scripts/tests/test_play_artifact.py`.
1. Write stdlib unittest fixtures for APK/AAB inventory, one supported ABI, required native libraries, ELF identity and 16 KB PT_LOAD alignment, trusted runtime metadata, assets preserved uncompressed, and missing/foreign/malformed inputs. Add obvious credential/build-debris path checks; explicitly limit what a path scan proves.
2. Run `python3 -m unittest discover -s scripts/tests -p 'test_play_artifact.py' -v`; observe failures before implementation.
3. Implement a read-only ZIP/ELF inspector, no extraction of archive paths and no secret contents in output. Exit nonzero on failed checks; produce structured measurements including raw artifact size, not invented Play download size. For AAB, document that ZIP inventory cannot replace bundletool/Console validation of protobuf manifest/delivery size.
4. Re-run fixtures and inspect actual unsigned release APK/AAB. Record real failures as blockers, not waived passes.
5. Commit checker and tests.

### Task 3: Reproducible unsigned App Bundle and SDK/tooling requirements

**Modify:** `Makefile`; `android/app/build.gradle.kts`, `android/build.gradle.kts`, `.github/workflows/verify.yml` only if evidence requires tooling/SDK updates.
1. Add `make bundle-release` running `:app:bundleRelease` and `make check-play-artifact` invoking the checker with expected ABI and explicit artifact path.
2. Use official policy evidence to determine required target/compile SDK, minimum AGP and compatible Gradle. Do not guess a future deadline or silently select new target behavior without verification. If an update cannot be safely verified locally, document the blocker rather than claim compliance.
3. Build unsigned bundle and inspect artifact. Signing remains intentionally absent.
4. Run existing script regressions and Android JVM/lint/APK/instrumentation compilation; document SDK-dependent/manual device follow-up.
5. Commit build preparation separately.

### Task 4: Licensing and provenance inventory

**Create:** `docs/release/dependency-inventory.md`; update `docs/release-license-audit.md` with links.
1. Inventory actual declared Android/native/guest/Python/npm dependencies, pinned versions/hashes and evidence locations. Separate direct declarations, resolved graph and guest package inventory from a complete license-cleared SBOM.
2. Identify notices/corresponding-source gaps, project-license decision, and exact source mapping still needed.
3. Do not choose a license or rewrite dependency legal terms. Commit findings.

### Task 5: Draft privacy, Data Safety and listing materials

**Create:** `docs/release/privacy-policy-draft.md`, `docs/release/data-safety-draft.md`, `docs/release/store-listing-draft.md`.
1. Trace local credentials/storage, provider prompt/context transmission, camera/location permissions, generated-code/network behavior and logs. Do not read user credentials, inspect photos or record coordinates.
2. Draft factual beta risk text, third-party provider caveats and review checklists with explicit owner-confirmation fields. Camera access does not mean photos are automatically uploaded; do not declare data types never collected when agents/generated apps may transmit them.
3. Explain no backup guarantee, unrestricted same-UID execution and limits of Restore. No invented telemetry absence for arbitrary generated apps.
4. Commit drafts as non-publication-ready materials.

### Task 6: Integration verification and final release gate

**Create:** `docs/reports/google-play-preparation.md`; update `TODO.md`, `README.md` with preparation links.
1. Run:
   - `python3 -m unittest discover -s scripts/tests -p 'test_play_artifact.py' -v`
   - `bash scripts/tests/apk-inventory-test.sh`
   - `bash scripts/tests/runtime-tools-test.sh`
   - `cd android && ./gradlew :app:testDebugUnitTest :app:testReleaseUnitTest :app:lintDebug :app:lintRelease :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:bundleRelease --console=plain`
2. Audit unsigned actual artifacts; compare checks with report and official policy. An inspection failure is an unresolved release gate, not a task to conceal.
3. Review final diff, artifact package metadata and preservation of pre-existing runtime metadata modification. Do not upload/install or commit generated artifacts.
4. Record completed local work vs outstanding Play policy interpretation, signed/device/16 KB acceptance, Play Console testing/account requirements, legal/privacy/signing/publication decisions.
5. Commit final documentation and leave branch separate from `main`.

## Completion definition

Local preparation and verified tooling may be complete while public-beta publication is blocked. Report exact test/build/audit results and unresolved gates. Owner steps and Google approval cannot be completed autonomously. Never describe the unsigned AAB or provisional privacy/legal drafts as ready to publish.
