# Google Play Data Safety — working draft, not Console answers

Consulted 2026-10-07: [Data Safety guidance](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en)
and [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311).
Applies to closed/open/production tracks. Internal-only testing exemptions do not
make a public beta exempt. Final answers must describe the shipped app, dependencies,
agent runtime and reasonably supported generated-app behavior, not just Android APIs.

## Terms and limitations

Collection generally means transmitting data off-device. Local-only processing
can differ from collection. A provider acting as a service provider may qualify
for a sharing exception, but this does not automatically eliminate collection.
Assess user-initiated transfer exceptions and purposes using Google's actual
criteria, rather than assuming that entering a prompt or choosing a provider
makes all transfers exempt. Ephemeral processing must still be assessed accurately.

Do not check “no data collected” based on local Flask/WebView alone. AI prompts
and context leave the device. Conversely, an Android location permission does not
by itself prove that Seed transmits every location fix off-device.

## Candidate data mapping to confirm

| Flow | Local / off-device | Candidate Console category | Decision still required |
| --- | --- | --- | --- |
| Chat prompts, model responses/context, generated code/tool output | Requests/context sent to selected AI provider; local files also possible | Other user-generated content; messages if applicable to final flow | Exact categories, optional/required, processing purposes, provider relationship/retention |
| Provider credentials/auth | Local private runtime/settings; used for provider authentication | Assess user IDs/other personal information as appropriate; do not guess credentials category | Credential types, account linkage, deletion, transmission and protection |
| Generated app databases/uploads/files | Local by default; arbitrary generated code/agent may transmit | Contents determine personal info, files/documents or other content | Supported transmission paths and policy scope; no blanket “never collected” claim |
| Camera preview | Returned locally to consented requesting page; page may transmit | Photos/videos when off-device transfer occurs | Whether final supported behavior transmits, purpose, optionality and disclosures |
| Approximate/precise location | Native foreground one-shot; requesting page may transmit | Approximate / precise location when collected | Final flows and grant levels; no background-location claim |
| Sensors | Foreground page readings; generated code may transmit | Determine category from actual data/use | Whether any covered data leaves device; do not invent a sensor checkbox |
| Logs/errors/tool results | Local runtime and possible model context; support/crash services not established | Diagnostics or user content depending on actual flow | Final crash/support collection, redaction, retention |
| Connection metadata | Provider/network endpoints receive requests | Determine whether provider collects device/other identifiers under policy | Provider terms, IP handling and any third-party SDK behavior |

## Evidence and exclusions

- Source architecture uses on-device backend/Flask and external provider-backed Pi
  requests; provider configuration and environment plumbing need final review.
- Manifest requests INTERNET, network state, notifications, dataSync FGS and
  coarse/fine location; no background location, CAMERA or shared-storage permission.
  External camera capture still handles sensitive photos.
- Credentials reside in plain Pi auth.json with private permissions; role sessions
  can persist conversations and context. Guest metadata includes Pi telemetry and
  OpenTelemetry packages; enabled collection is not established by this inventory.
- No advertising or developer-cloud-backup feature is described in the current
  implementation. This is not a guarantee about all transitive/provider behavior
  or arbitrary generated code. Verify any SDKs and future integrations.
- Capability consent, TLS transport, private Android storage and cryptographic
  at-rest encryption are distinct. Do not promise all-data encryption or safe
  unrestricted execution.

## Owner approval checklist

1. Confirm developer/legal identity, advertised providers and their contracts.
2. Trace release network behavior and actual SDK/module configuration; assess all
   applicable data categories, collection/sharing exceptions, purposes and
   optional versus required processing.
3. Confirm in-transit encryption for all covered flows, not merely the AI client.
   Local cleartext loopback is used; arbitrary outbound generated requests need
   separate assessment. Do not mark this answer automatically.
4. Define accurate data-retention/deletion procedures and a public privacy URL;
   add the policy link/text within the app before submission.
5. Confirm account creation/deletion requirements if accounts are introduced;
   provider credentials alone do not establish a Seed account system.
6. Review permission-specific disclosures and the upcoming location requirements.
7. Enter and approve Console answers personally; attach evidence and date them.

These drafts cannot close the privacy release gate. There is no published policy,
verified declaration, or guaranteed provider-data deletion supplied by this branch.
