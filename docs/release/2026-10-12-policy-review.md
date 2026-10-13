# Google Play public-beta policy review — 2026-10-12

**Not cleared for submission.** Official pages below were fetched directly over
HTTPS (HTTP 200) on 2026-10-12; this review does not rely on the older draft's
policy claims. No Play Console account or signed release was inspected. Read
README, release/privacy/Data Safety/listing drafts, Gradle, manifest,
`RuntimeService.kt`, Settings UI and relevant source searches. No code or constants
were changed. This is a policy assessment, not Google approval or legal advice.

## Verified requirements and Seed implications

### 1. Public beta / open testing

[Google's account-testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en)
apply to **personal accounts created after November 13, 2023**: at least **12
testers continuously opted in for the preceding 14 days**, then an application
for production access. Approval, not just elapsed time, unlocks open testing.
The application asks about testing, feedback and readiness. Open testing makes
the app/listing publicly visible; calling it beta does not exempt it from policy.

**Blocker:** account type/date, verification and production access are unknown.
Owner must check Console. Do not promise immediate open testing or describe
12/14 as a universal requirement for all account types.

### 2. Target API and 16 KB

[Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
state new phone apps and updates must target **Android 16 / API 36 from August
31, 2026**. An extension to November 1 is mentioned, but no extension is verified
for this app. Current Gradle already has compile/target SDK 36 and min SDK 26.

[Current 16 KB guidance](https://developer.android.com/guide/practices/page-sizes)
states API 35+ apps must support 16 KB on 64-bit Play devices. It explicitly says
incompatible **updates** cannot be released starting **February 1, 2027** (page
last updated September 16, 2026). That sentence is not evidence that a new app
may ignore compatibility today. Do not repeat an older deadline as the live rule.

**Blocker:** local alignment reports are not runtime acceptance. Verify the
actual delivered ARM64 artifact, including PRoot, guest ELF executables/libraries,
PTY and app operation, on a real 16 KB environment. `useLegacyPackaging=true`
addresses compressed/extracted JNI delivery, not ELF or runtime page-size bugs.

### 3. Executable downloads, PRoot and self-modification — highest policy risk

[Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379?hl=en)
says an app may not “modify, replace, or update itself” except through Play and
may not download executable code such as dex/JAR/.so from elsewhere. Its exception
covers code running in a VM/interpreter providing indirect Android API access.
Runtime-loaded Python/JavaScript must still not allow potential policy violations.

**Interpretation, not approval:** generated Python/JS can be relevant to the
interpreter exception. PRoot translating native Linux execution is not itself
proof of that exception. Native packages fetched after installation are materially
different from bundled native packages delivered through Play. An immutable APK
alone does not settle how Google's self-update rule applies to a mutable backend
and generated app. Same-UID agents also make privacy/security claims harder to keep.

**Concrete conditional download path:** the parallel security audit inspected
`android/app/src/main/assets/linux/rootfs.tar.gz`; its
`usr/local/lib/node_modules/@earendil-works/pi-coding-agent/package.json` identifies
Pi **0.84.2**. Within that package, `dist/utils/tools-manager.js:212-278` downloads
native `rg`/`fd` GitHub archives and extracts/chmods them. `ensureTool:287-324`
invokes this when tools are missing and `PI_OFFLINE` is not enabled. Callers include
`core/tools/grep.js:99,148`, `core/tools/find.js:161` and
`modes/interactive/interactive-mode.js:728-729`. No executable `rg`, `fd` or `fdfind`
was found in that rootfs; guest Linux Node does not take the Android-platform
exception. This is a concrete dependency code path, not an observed download:
network success and downloaded-binary compatibility were not tested.

In contrast, this bundle's `update --models` path in
`dist/package-manager-cli.js:350-376` reaches `ModelRuntime.refresh` and
`core/remote-catalog-provider.js`, fetching JSON from
`https://pi.dev/api/models/providers/<provider>`. Do not mislabel this inspected
model-only update as a native installer or generalize it to future/configured Pi
updates. The catalog endpoint also belongs in the network/privacy inventory.
These artifact findings come from the parallel security audit, not this policy
review's own source inspection.

**Blocker:** unrestricted shell/agent execution can fetch native executables and
change more than the generated page. Prompt instructions are not enforcement.
Resolve the Pi fallback path as well as shell/package-manager/native-extension
paths; prebundling a tool does not by itself remove an enabled download fallback.
Document actual package-download, native-extension and mutation paths; seek a
specific policy determination for this architecture or make an explicit owner
choice about enforceable boundaries/distribution outside Play. Do not silently
remove accepted agent freedom or claim all self-modification is either approved
or categorically banned. Also review the same policy's warning about JavaScript
interfaces loading untrusted HTTP content against the actual WebView boundary.

### 4. Generative AI safety and reporting

[AI-generated content policy](https://support.google.com/googleplay/android-developer/answer/13985936?hl=en)
includes text-to-text chatbots where conversation is central. It requires
preventing restricted/deceptive content and **in-app reporting/flagging to the
developer without leaving the app**, with reports used for filtering/moderation.
No blanket coding-assistant exemption appears on this policy page.

**Blocker:** Seed's central coding conversation likely falls within scope.
No corresponding reporting UI was found in the inspected source. Implement a
real submission path and review process, not just an external email link or
local-only flag. Establish harmful-content prevention across supported providers
and generated outputs; provider choice does not establish compliance. Any report
upload itself requires a privacy/data-flow review and protection for keys/context.

### 5. Privacy and Data Safety

[User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en)
requires a privacy policy in Console and link/text inside the app. Its public URL
must be active, non-geofenced, non-editable and not a PDF. Include app/developer
identity, contact, data access/use/sharing, secure handling and retention/deletion.
Unexpected sensitive-data handling needs the required prominent disclosure and
consent; an Android permission alone does not explain later transmission.

[Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
requires the form for **closed/open/production** tracks; exclusively internal
testing has an exemption. Off-device transmission generally counts as collection,
including provider requests. Local-only processing is distinct. Service-provider
and qualifying user-initiated transfers can be exceptions to **sharing**, not an
automatic exemption from collection. Ephemeral off-device processing still belongs
in the form response. Account deletion requirements apply if the app offers
in-app account creation; entering an existing provider key is not by itself proof
of a Seed account system.

**Blockers:** current drafts contain owner placeholders; no in-app privacy link
was found. Confirm developer identity/contact, provider terms/roles, credentials,
prompt/file/tool context, photos/location, telemetry, backups and generated-code
network behavior. Do not declare “no data collected,” universally encrypted
storage, no telemetry or complete remote deletion without evidence. The existing
draft correctly distinguishes private storage from encrypted credentials. A
capability consent dialog is not a restriction on subsequent generated-code use.

### 6. Foreground service: `specialUse` is possible to propose, not approved

[Android service types](https://developer.android.com/develop/background-work/services/fgs/service-types)
define `specialUse` for valid cases not covered by another type. It needs
`FOREGROUND_SERVICE_SPECIAL_USE`, `foregroundServiceType="specialUse"` and a
service property named `android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining
the use case. The explanation is reviewed in Console.

[Play FGS guidance](https://support.google.com/googleplay/android-developer/answer/13392821?hl=en)
confirms all types are reviewed and requires use-case details, impact of deferral/
interruption and a demonstration video. The [policy](https://support.google.com/googleplay/android-developer/answer/9888379?hl=en)
requires a core user benefit, user initiation or perceptibility, user termination,
necessity against deferral/interruption, and running only as long as needed.
`specialUse` does not exempt a permanently idle runtime from these conditions.

**Current code:** manifest still declares `dataSync`; service is `START_STICKY`
and notification has no explicit Stop action. No `onTimeout` override was found.
`dataSync` includes local file processing, but that does not justify arbitrary
indefinite runtime hosting. On Android 15+ for applicable targets, its [timeout](https://developer.android.com/develop/background-work/services/fgs/timeout)
is six background hours per 24 hours, with prompt `stopSelf()` after `onTimeout`.

**Blocker:** decide and substantiate the genuine use case/lifecycle. `specialUse`
is a candidate for review, not an automatic fix. Validate user stop, no unwanted
restart, background duration and cleanup in the final target-36 build.

### 7. BYOK, external provider charges and billing

[Payments policy](https://support.google.com/googleplay/android-developer/answer/9858738?hl=en)
generally requires Play Billing for in-app digital purchases, including cloud
services. Outside-payment links and signup flows are restricted except applicable
regional/program exceptions. Do not assume those exceptions apply globally.

[Official Payments FAQ](https://support.google.com/googleplay/android-developer/answer/10281818?hl=en)
allows consumption-only apps, including access to services paid for elsewhere,
provided purchases cannot be made in-app. It permits some purchase information
without direct links; this is not unrestricted permission to steer to checkout.

**Interpretation:** accepting an existing provider API key with no in-app purchase
is plausibly consumption-only; the sources do not explicitly approve BYOK AI
apps. No Billing flow or payment links were found in inspected Android/backend
source, but provider login/help/terminal/generated flows still need review.
Provider credits, paid Seed features or subscriptions require a new assessment.
Keep charges clear in the listing; do not add “buy credits” links or paid signup
funnels without resolving policy/program eligibility. Do not add Play Billing
solely because an external provider may charge an existing account.

## Decision summary

Before public beta: resolve architecture eligibility; implement AI reporting and
safety handling; publish accurate privacy disclosures/Data Safety; substantiate
the FGS type and stop lifecycle; validate delivered 16 KB behavior; confirm account
track access and BYOK presentation. Licensing, signing, reviewer access and actual
release-device acceptance remain separate gates from this policy review.
