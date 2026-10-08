# Play Release Engineering Batch Implementation Plan

> **REQUIRED SUB-SKILL:** Use the executing-plans skill to implement this plan task-by-task.

**Goal:** Remove the measured Termux ELF-alignment failure, make metadata inventories reproducible, enforce an explicit build-then-audit release gate, and audit/tighten runtime permissions and shutdown without changing accepted agent freedom.

**Architecture:** Rebuild exact pinned Termux native source rather than editing ELF headers or upgrading unrelated APIs. Keep runtime metadata/native bundle untouched. Add deterministic metadata tooling and a separate strict release-check target. Harden only verified app-private permission/lifecycle paths; retain owned-process shutdown and fail-closed launch/writer exclusion.

**Tech Stack:** Android Gradle/Kotlin, pinned Termux/Android NDK, Python stdlib/unittest, Make/Bash, Markdown.

## Scope / exclusions

Branch: `app-store-preparation`. No uploads, device installs/uninstalls, generated runtime replacement, permanent broken-runtime backups, migration or release-key creation. Preserve the pre-existing `seed_version.json` change. Logo is deferred. Owner selected GPL in principle, but exact version/only-or-later is awaiting confirmation; do not write an ambiguous LICENSE. Third-party terms remain unchanged. No agent isolation, new FGS type declaration, or claim of Play eligibility.

## Task 1 — verified Termux native rebuild

**Files:** `android/app/build.gradle.kts`, new pinned native source/provenance and reproducible build helper/task, native/source contract regressions.
1. Reproduce current inspector failure and trace it to exact terminal-emulator 0.118.3 JNI, inspect upstream native sources/build and Apache module exception.
2. Pin exact source/commit and a compatible NDK; retain source/license/build modifications. Preserve Java/JNI signatures and terminal behavior.
3. Write failing source/build/artifact regression before integration. Recompile with genuine 16 KB-compatible ELF settings; never edit p_align to fake support.
4. Preserve pinned AAR Java/resources/consumer metadata and replace its native code deterministically; prevent duplicate/transitive legacy JNI selection.
5. Build and inspect APK/AAB for both ABI recipes where practical. Mechanical alignment success is not 16 KB runtime/device acceptance.
6. Commit verified source/build changes separately. Document SDK/license/tool prerequisites and remaining functional acceptance.

## Task 2 — reproducible package metadata

**Files:** `scripts/inventory-runtime.py`, `scripts/tests/test_runtime_inventory.py`, `docs/release/runtime-package-metadata.json`, dependency inventory docs.
1. Write failing tar metadata fixtures for Alpine installed DB, Python dist-info headers, nested/versioned npm manifests, missing/invalid fields, bounded reads, traversal/symlinks and deterministic output.
2. Implement read-only streaming archive scan; never extract tar paths or read auth/settings/user contents. Record actual source SHA/size and sort metadata deterministically.
3. Reproduce the real 65/49/311 metadata snapshot; explain counts vs unique components and SBOM/license limitations.
4. Commit generator/tests/documented command and verified regenerated metadata.

## Task 3 — strict release check

**Files:** `Makefile`, script/Make source-contract tests, release docs.
1. Keep `bundle-release` as an unsigned build task. Add `release-check` that finishes a fresh bundle build before inspecting that exact output, even under `make -j`.
2. Require both steps to propagate errors; never inspect stale output or waive alignment failures. Preserve explicit ABI/path parameters and no implicit runtime generation.
3. Add red/green fixture tests proving build failure stops audit, failed audit fails target, successful ordering, and path/ABI propagation without building or installing real apps.
4. Run the actual strict gate, record result and commit tooling/docs.

## Task 4 — permissions / lifecycle audit

**Files:** `GuestDns.kt`, `RuntimeExtractor.kt`, relevant JVM tests; lifecycle audit/report. If safe shutdown implementation is added, `RuntimeService.kt` plus a testable coordinator/regressions and native UI strings.
1. Confirm ownerOnly=false expands everybody bits; don't suppress warnings claiming it is owner-only.
2. Tighten known app-private DNS/new extraction/deletion modes to exact owner access where compatible with same-kernel-UID PRoot and executable archive semantics. Preserve generated app/config permissions/data during Restore.
3. Audit user stop and API35+ FGS timeout against service destruction, cancellation-resistant launches, terminal ownership and retained factory state. No unverified tracer kill or guessed PID signals.
4. Only implement shutdown after a tested design confirms freeze-before-exit and cleanup that survives serviceScope cancellation. Keep FGS type dataSync unchanged; applicability/Console justification remains an owner/policy gate.
5. Run regressions and full debug/release JVM/lint/APK/AAB/instrumentation compilation. Do not claim physical lifecycle acceptance.
6. Commit audit/hardening separately and update exact remaining gates.

## Final verification / handoff

Run all script unittest fixtures, existing Bash suites, both Android JVM/lint/build variants and instrumentation compilation, real APK/AAB inspection and `make release-check`. Refresh measured artifact hashes/bundletool evidence for changed artifacts instead of carrying old fingerprints forward. Review diffs and untouched source metadata. Report any blocked build/dependency/license prerequisite honestly. Keep branch separate from main; no publication. Signed release/new package/Android15-16/16KB device acceptance, unrestricted-code policy, AI safeguards/reporting, licensing/source obligations and final privacy/signing/Console steps remain separate gates.
