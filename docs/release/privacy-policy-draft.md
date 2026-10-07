# Seed beta privacy policy — OWNER REVIEW DRAFT

**NOT PUBLICATION-READY.** Do not publish this file or submit it as a completed
policy. Replace confirmation fields, check the final release and provider terms,
and host an accessible public policy linked from Play Console and inside Seed.
The developer must approve its accuracy and applicable legal requirements.

- Developer / controller: **[OWNER REQUIRED: legal identity and address where required]**
- Contact and privacy requests: **[OWNER REQUIRED: monitored contact]**
- Effective date and public policy URL: **[OWNER REQUIRED]**
- Audience / countries / age restrictions: **[OWNER REQUIRED]**

## What Seed does

Seed runs an embedded Linux/Python runtime on your Android device. You can converse
with an AI coding agent to create and change a local web application. You choose
an AI provider and configure access credentials. The first beta is intended for
ARM64 devices; support and limitations must match the final listing.

## AI providers and off-device processing

Using AI features sends conversation prompts and task context to the configured
provider. Depending on the work, context can include generated code, file contents,
errors, tool results and data the agent reads. The provider receives credentials
or authentication needed to perform requests. Network connections can disclose
ordinary connection metadata such as IP address to the receiving service.

**[OWNER REQUIRED: identify supported/advertised providers, relevant privacy terms,
retention/training settings, international-transfer arrangements and applicable
service-provider roles. Do not promise zero retention, no training, or universal
provider deletion without evidence.]** Do not enter sensitive information you are
not authorized to send. Disconnecting Seed does not erase provider-held data.

No developer-operated cloud backend is part of the currently documented runtime
architecture. This is not a guarantee that all data stays on the device: providers,
agent commands and generated applications can make network requests. The owner
must confirm whether support, analytics, crash collection, updates or other
operational services will be added to the release.

## Device-local storage

Seed stores settings and credentials in its private Android app storage and stores
the runtime, generated applications, databases, uploads and agent-related files
locally. The generated app can also use browser storage. Provider credentials are stored
in Pi's plain JSON `auth.json` with private file permissions, not encrypted at
rest. The legacy Android encrypted store is migration-only. Private app storage
and cryptographic encryption are different protections, and the Linux/agent
runtime shares execution authority. Role sessions can persist prompts, responses
and tool context even when the chat UI's durable replay is incomplete.

**[OWNER REQUIRED: final retention/deletion behavior, backup configuration,
Android device-transfer behavior, support access and any legal retention rules.]**
Current durable chat/history behavior is limited; local persistence is not a backup.
The new `cz.trety.seed` installation does not automatically migrate the old
`com.seed.app` installation's private data.

## Camera, location and sensors

Generated app pages can request supported device capabilities through Seed's
native interface. Seed asks **Allow once / Allow / Deny**, independently of
Android permissions. Remembered capability consent can be revoked in Settings.

- Camera capture opens the device's camera UI after consent. The user takes or
  cancels a photo; Seed does not automatically take photographs. A captured preview
  is returned to the requesting generated page.
- Location is a foreground, one-shot request. Approximate/precise access depends
  on consent and the Android grant. There is no declared background-location
  permission. Seed does not intentionally log location coordinates.
- Sensor access can supply one-shot or page-owned live readings, with lifecycle
  cancellation rather than automatic background restart.

Returning a photo, coordinate or sensor reading to a generated page is not itself
proof that the data will never leave the device. That page or agent-generated
code may save or transmit it. Permission/consent screens must accurately explain
unexpected downstream use. **[OWNER REQUIRED: final supported data flows and
whether additional disclosures or restrictions are required.]**

## Experimental execution and recovery

The agents and generated Python are not isolated from the rest of Seed's private
runtime. Incorrect or malicious commands can alter or delete data and expose
credentials. Native capability consent is not a sandbox for shell/Python code.
Use test credentials and noncritical data during beta testing.

Restore replaces bundled runtime/backend files and preserves the documented
application/config boundary plus outside-runtime storage. Other files inside the
Linux runtime can be discarded. Restore does not repair arbitrary generated code,
recover already-deleted data, undo disclosure or provide a permanent backup.

## Your choices and requests

You can avoid AI requests, deny/revoke capability consent and use Android permission
controls. **[OWNER REQUIRED: verify settings/key removal and deletion UI in the
final build; describe provider and developer-held data request procedures.]**
Uninstalling an app generally removes its private on-device storage, but does not
necessarily delete remote data, device backups or the separate older installation.
Do not advertise immediate complete deletion without validating these cases.

## Developer disclosures to finalize

The bundled guest includes Pi telemetry/OpenTelemetry packages; their presence is
not proof that telemetry is enabled. Verify configuration and emitted traffic
before making claims about diagnostic collection or third parties.

Confirm whether any accounts, payments, advertisements, telemetry, support uploads,
remote backups, sharing services or children's use are present. Planned server
backups/sharing/paid extensions are ideas, not current release features. Any added
service requires a fresh policy and Data Safety review before distribution.
