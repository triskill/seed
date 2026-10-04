# Future product ideas

These are exploratory ideas, not committed scope, implemented capabilities,
pricing decisions, or a prerequisite for the current development version.

An extended version of Seed could offer:

- Server-backed backups of generated apps and their data, with explicit restore
  and rollback to earlier versions.
- Broader backup/export options beyond bundled-runtime repair, including a clear
  inventory of saved files, databases and browser state.
- Sharing generated apps or projects, with deliberate handling of private data,
  provider credentials and Android device grants.
- Optional paid features or a paid extension supporting hosting, backups, sharing
  and related services. No subscription or price has been decided.

Before implementation, define consent, encryption/key ownership, retention,
account/export/deletion flows, database consistency, compatibility and the boundary
between project sharing and private user data. Never upload credentials or personal
content merely because an agent requests it.

## Current recovery scope

Native bundled-runtime restoration is not a backup system. The agreed scope is to
replace broken runtime/backend files, preserve specifically identified app/data
locations, and discard the replaced runtime after transaction completion. There is
no permanent copy of the broken runtime. Temporary staging/journaling used to
finish or roll back an interrupted replacement is transaction safety, not a
user-facing backup or historical version store.

Existing data stored outside documented preservation locations may be removed by
restoration. Lost data cannot be recovered without a backup; runtime repair also
cannot undo credential disclosure or automatically repair erroneous generated code.
