# Google Play public beta — owner checklist

**Not ready to publish.** Public beta means **Open testing**, not Internal testing.
Do not upload until the engineering/policy gates in [readiness](google-play-readiness.md)
are closed. Never put keys, passwords or tester personal data in this repository.

## 1. Decide the product scope first

- [ ] Resolve executable-code eligibility. Seed's unrestricted agents/shell can download
  and execute native programs outside Play. The interpreter exception does **not**
  automatically permit this. Ask Play policy support about the actual behavior and
  keep their response. Choose a compliant restricted Play build, a substantiated
  accepted design, or a different distribution route. Restrictions need your approval.
- [ ] Approve AI safeguards and a real **in-app report** workflow: who receives reports,
  the HTTPS service, minimal report data, retention and who reviews/acts on reports.
  An email link or reports saved only on the phone is not a completed reporting service.
- [ ] Choose the foreground-runtime behavior: only while actively used, or a justified
  background use case/type with a Console declaration and demonstration video.
  Do not select `specialUse` merely to avoid `dataSync` time limits.

## 2. Account and testing access (start early)

- [ ] Create/verify the Play developer account and confirm personal vs organization,
  creation date, identity/contact/device checks, and available test tracks in Console.
- [ ] For personal accounts created after **13 November 2023**, run a genuine closed
  test with **12+ testers continuously opted in for 14 days**, then apply for production
  access. Open testing requires that approval. Recruit testers and collect feedback;
  simply waiting 14 days does not guarantee approval. Otherwise follow your account's gates.

## 3. Legal, privacy and support

- [ ] Confirm the exact project license (GPL version and **only / or later**), copyright
  holder, and redistribution rights. Have required third-party notices and exact
  corresponding source/build instructions prepared for the shipped binaries/rootfs.
- [ ] Provide developer/legal identity, monitored support/privacy contact, intended
  countries and audience/age range. Approve provider terms, retention/deletion,
  telemetry and all off-device data flows. See [privacy draft](privacy-policy-draft.md)
  and [Data Safety draft](data-safety-draft.md); they are **not submission answers**.
  The [licensing gap report](../reports/2026-10-12-license-gap.md) lists exact
  source and notice work needed for the bundled native and guest components.
- [ ] Publish an accessible, public, non-geofenced HTML privacy policy (not a PDF),
  then give engineering its final HTTPS URL for an in-app link. Approve needed
  prominent disclosures/consent. Review Data Safety for the actual signed release.
- [ ] Confirm provider/BYOK and any paid-service model against Play payments rules;
  do not add purchase links or promise an exemption without review. No Seed billing
  or Seed account creation is currently established.

## 4. Signing, listing and reviewer access

- [ ] Confirm permanent package **`cz.trety.seed`**; it does not upgrade `com.seed.app`.
  Preserve old app data. Set up Play App Signing and a securely backed-up upload key.
  Keep passwords/keystores out of Git and chat; use the
  [owner-only signing handoff](google-play-signing-handoff.md) after release gates close.
- [ ] Approve name/descriptions, 512×512 Play icon, 1024×500 feature graphic and
  at least two genuine phone screenshots. Complete audience, content rating, ads,
  App access, Data Safety and foreground-service declarations as Console requests.
- [ ] Provide reviewer instructions and working, limited test access for provider-gated
  features through Console's private App access area. Reviewers must be able to test
  the real app without using your personal credentials or paying to unlock review.

## 5. Test the final signed build, then authorize release

- [ ] Supply an authorized ARM64 test device and a **16 KB-page** device/emulator.
  Test Play-delivered release on Android 15/16: first launch, provider/chat/generated
  app/terminal, deny/grant permissions, offline/error paths, Stop/timeout/restart,
  rotation, update/data preservation and explicit Restore with disposable data.
  Use the [device acceptance sheet](google-play-device-acceptance.md) to record
  results. ELF alignment/JVM success is not this acceptance. Automatic backup/transfer
  is disabled; keep your own safe copy of important generated data before device changes.
- [ ] Review the signed AAB, increment `versionCode` for each new upload, inspect
  Play delivery size/device compatibility, run pre-launch reports and fix findings.
  Start with Internal testing; then Closed testing if required; finally Open testing.
- [ ] Approve countries/tester limits, release notes and rollout in Console. Do not
  select Production by mistake. Verify the opt-in/store link with a separate tester.

Official requirements and evidence: [policy review](2026-10-12-policy-review.md).
These account actions, approvals, tests and Google review cannot be replaced by local code changes.
