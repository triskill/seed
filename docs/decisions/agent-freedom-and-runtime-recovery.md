# Decision: unrestricted agents, recovery rather than isolation

Status: accepted by the user during development.

## Decision

Keep the worker's general-purpose file and shell capabilities. Do not implement
restricted worker tools, companion APKs, isolated execution services, or pretend
that Linux users inside PRoot provide a security boundary. Worker isolation is
not an outstanding implementation commitment: this is an explicit accepted risk,
not an accidental omission or an isolation technical-debt task.

The benefit is agent flexibility and variability. The accepted cost is that a
mistaken or incapable model can damage the generated app, backend, Python/runtime,
settings or other files accessible under Seed's Android UID. Generated Python has
similar authority. PRoot and shared-UID mode bits do not isolate these processes.
This is not appropriate protection against hostile code or untrusted agents.

## Recovery direction (not yet implemented)

Prefer an explicit native Android Settings action to restore the known-good
runtime/backend from the installed APK. It must work without a healthy Python
backend, stop and confirm exit of owned processes before replacing runtime files,
and distinguish full restoration from the existing process Restart action.

Default restoration must preserve credentials/provider settings, generated-app
files/databases, and WebView/browser storage. Do not clear app data or use wholesale
re-extraction that silently overwrites these. Inventory storage boundaries and
bundle availability before implementation; no preservation guarantee is established
by this decision alone. Any destructive reset must be separate and confirmed.

A damaged generated app needs a separate explicit rollback/backup path; preserving
its files while restoring Python does not repair bad Python app code. Backups under
the same UID are not tamper-proof. Runtime restoration cannot recover missing data
without a backup or undo credential disclosure; exposed secrets require rotation.

## Current implementation versus decision

Bounded role recovery and owned-process Runtime Restart are implemented. APK startup
also deploys selected backend modules and role prompts. None of these is a complete
Settings-based runtime restore. Logging this decision adds no restore implementation
or new sandbox guarantees. Restoration design/implementation requires its own task.

## Revisit

Revisit this choice before offering hostile-code execution, stronger security
claims, sensitive deployments, or when observed failures outweigh agent flexibility.
