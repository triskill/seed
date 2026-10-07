# Google Play beta readiness — not cleared for publication

Review date: **2026-10-07**. Package: **cz.trety.seed**. Intended first artifact:
one direct-native **arm64-v8a** runtime. This is a preparation assessment, not
Google approval, legal advice, or a declaration that the app meets every policy.

## Current release gates

| Gate | Finding / next action | Ownership |
| --- | --- | --- |
| Target SDK | Baseline target/compile SDK 34 is insufficient; prepare API 36 with compatible tooling. Building does not establish Android 15/16 functional acceptance. | Local preparation; device acceptance outstanding |
| Downloaded/executed code | Generated Python/JS may fall within the interpreter exception; unrestricted shell can also fetch/execute native packages or binaries. Prompt instructions are not an enforcement boundary. Resolve eligibility without silently reversing the accepted agent-freedom decision. | Owner / policy review |
| AI-generated content | Central conversational generation likely falls within the AI policy. In-app reporting/flagging and harmful-content prevention need design/implementation; no coding-assistant exemption was established. | Product/policy decision and implementation |
| Foreground service | `RuntimeService` declares `dataSync`; justify the actual use case and Console declaration. It has no `onTimeout` implementation. Targeting newer Android introduces duration/stop requirements; a persistent local runtime is not automatically data synchronization. | Engineering / owner declaration |
| Native 16 KB support | Audit every packaged native ELF, including transitive libraries. Alignment is necessary, not proof that PRoot and the guest runtime function on 16 KB devices. | Local audit plus device/emulator acceptance |
| Bundle delivery | Build unsigned AAB, inspect inventory, and measure device-specific compressed delivery using bundletool/Play. Raw AAB/APK size is not download size. | Local tools; Console validation outstanding |
| Licensing | Project license, notices and exact corresponding-source/provenance obligations remain unresolved. | Owner/legal review |
| Privacy / Data Safety | Drafts require confirmation of developer identity, contacts, third-party data use, collection categories and actual generated-code behavior. Publish an accessible policy and expose it inside the app. | Owner plus implementation |
| Signing / account / testing | Upload-key custody, Play App Signing, account verification and track access require owner participation. | Owner |
| Release acceptance | Test the signed `cz.trety.seed` artifact, first install, provider flow, permissions, background service, generated app, update preservation and Restore. The old package's 52 debug tests are historical evidence, not new-package release acceptance. | Device acceptance |

## Official policy evidence

### Target SDK

[Target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878?hl=en)
state that from **August 31, 2026**, new mobile apps and updates must target
**Android 16 / API 36**. Existing-app discoverability thresholds are different.
Do not assume an extension to November 1 applies to a new Seed submission.

[AGP/API compatibility](https://developer.android.com/build/releases/about-agp)
requires at least AGP 8.9.1 for API 36; the preparation chooses a compatible pinned
toolchain and records verification separately. Retain older-device compatibility
where supported rather than making minimum SDK 36.

### App Bundles, architecture and size

[Dedicated maximum-size guidance](https://support.google.com/googleplay/android-developer/answer/9859372?hl=en)
currently lists **500 MB compressed download size for a base module**, 500 MB per
feature, and separate asset-pack/cumulative limits. Downloads above 200 MB can
show a mobile-data warning. [The general setup page](https://support.google.com/googleplay/android-developer/answer/9859152)
still surfaces a 200 MB limit: verify the actual Console decision rather than
promise admission based on inconsistent documentation.

[App Bundle delivery](https://developer.android.com/guide/app-bundle) splits native
libraries by device ABI, but does not automatically select different rootfs TARs
placed in base assets. Seed's generator intentionally produces one matching ABI
and rootfs at a time. Do not combine both guest architectures in ordinary assets
and assume Play will filter them. AAB ZIP bytes, extracted TAR bytes, installed
size, compressed device download and update-patch size are distinct measurements.

### Native page sizes

[16 KB guidance](https://developer.android.com/guide/practices/page-sizes) says apps
targeting API 35+ must support 16 KB memory pages on 64-bit Play devices. The live
page now explicitly states incompatible updates cannot be released starting
**February 1, 2027**; do not substitute an old deadline for the current transition
or assume new apps are exempt until then.

All native dependencies need suitable ELF load segments and runtime behavior.
`useLegacyPackaging = true` compresses/extracts native libraries and avoids the
uncompressed ZIP-alignment issue; it does not repair ELF alignment or hard-coded
page-size assumptions. Audit guest executables and functional runtime startup too.

### Executable code and unrestricted agents

[Device and Network Abuse](https://support.google.com/googleplay/android-developer/answer/9888379)
prohibits downloading executable code such as dex/JAR/.so from outside Play, with
an exception for code running in a VM/interpreter providing indirect Android API
access. Runtime-loaded interpreted code must not allow potential policy violations.

Seed's generated Python/JS and explicit native capability bridge are relevant to
that exception, but do not establish compliance. PRoot is not itself proof of an
eligible VM. Bundled build-time native dependencies delivered through Play differ
from post-install native executable downloads. The worker prompt discourages
package installation/outbound calls, but agents and generated Python retain
same-UID execution authority by explicit accepted decision. Do not advertise
prompt rules as a sandbox or add restrictions without a new owner decision.

### AI-generated content

[AI-generated content policy](https://support.google.com/googleplay/android-developer/answer/13985936)
includes text-to-text conversational AI where chatbot interaction is central, and
requires in-app user reporting/flagging without leaving the app. Seed's coding
conversation is central; no official blanket coding-assistant exemption was found.
Assess harmful-content prevention for generated responses and apps. External email
alone is not the stated in-app reporting mechanism. Store-artwork AI declarations,
where applicable, are a separate question.

### Privacy, permissions and foreground services

[User Data](https://support.google.com/googleplay/android-developer/answer/10144311)
requires a privacy policy both in Console and in the app, accurate developer
identification/contact details, and prominent disclosure/consent for unexpected
sensitive-data handling. [Data Safety](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
applies to closed/open/production tracks; internal-only tests have a limited
exemption. Collection generally concerns off-device transmission: local-only
processing differs from transmitting chat, photos or coordinates. Service-provider
exceptions may affect sharing, not automatically collection.

The current manifest requests coarse/fine foreground location, not background
location, and uses an external camera capture intent without CAMERA/storage
permission. Camera results are still sensitive data. Native consent is separate
from Android permission and does not prove what arbitrary generated code does
with returned values. [Permission guidance](https://support.google.com/googleplay/android-developer/answer/9888170)
also announces location changes effective **January 27, 2027**; plan for them,
without describing them as already effective today.

The declared `dataSync` foreground service needs an appropriate use case,
user-visible benefit/stop behavior and [Console FGS declaration](https://support.google.com/googleplay/android-developer/answer/9888379#fgs).
[Android foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout)
introduce a six-hour background budget for dataSync on Android 15 when targeting
API 35+, with a bounded `onTimeout` shutdown requirement. Current startup/recovery
checks do not constitute this lifecycle acceptance.

### Account-dependent testing

[Personal-account testing](https://support.google.com/googleplay/android-developer/answer/14151465)
requires personal accounts created after November 13, 2023 to complete closed
testing with at least **12 continuously opted-in testers for 14 days**, then apply
for production access. Open testing becomes available after that access is granted.
Account type, creation date, test engagement and approval are unknown. Public beta
availability cannot be inferred from owning trety.cz or building an AAB.

## Autonomous versus owner work

Local preparation can generate artifacts, checks, evidence and drafts. It cannot
select legal terms, promise provider retention/deletion, approve privacy answers,
satisfy real-tester continuity, obtain Google approval, or choose signing-key
custody. No uploads, device changes, production Restore or account actions are part
of this branch. See the final preparation report for actual verified results.
