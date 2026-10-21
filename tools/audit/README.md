# Isolated host audit

These tools execute recovered application code using synthetic data. They do
not discover credentials, connect to user accounts, use a phone, or write to the
Render service. `RISK_REPRODUCED` means a defect was demonstrated; it is not a
passing safety check.

## Kotlin data layer

`run_jvm_audit.py --source PATH --jars JAR_DIRECTORY --output OUTPUT_DIRECTORY`
compiles Models, AppStrings, NoteDatabase, SettingsStore, BackupManager and
AttachmentStore from the selected Android source tree. It also compiles the
exact non-UI prefix of CloudSync.kt. Its only network transformation replaces
the hard-coded production URL with a loopback-only synthetic HTTP server.
Compose imports/UI are omitted. All transformed inputs are hashed in output.

The host adapter uses SQLite JDBC for the real SQL and approximates a small
Android API surface. It matches Android `insert()` returning -1 on SQL failure.
SharedPreferences are an in-memory implementation; filesystem operations use
new temporary directories. Notification/calendar scheduling is stubbed.
This is not Android instrumentation and does not verify lifecycle, UI, system
permissions, Android SQLite locking, notifications or iOS compatibility.

### D04 regression suite

Add `--suite backup-validation` to the command above to run only the backup
preflight/restore tests. This suite does not compile CloudSync or start an HTTP
server. It tests all eight collections, local preferences and binary attachments
using temporary synthetic data. Every rejected input is checked against both
exported data and SHA-256 hashes of the SQLite file, attachment files and index.
It also tests complete empty/nonempty JSON/ZIP files, legacy file schemas 3–5,
and empty/nonempty cloud schema 7 snapshots without local-only sections.

### D05 restore integrity suite

Use `--suite restore-integrity` to test duplicate identities in local/ZIP/cloud
backups and SQLite rollback during replacement. Real SQLite temporary triggers
raise errors on the first/second inserted row in every restored table and on
each table's deletion. These tests check all existing data and file hashes,
then remove the trigger and verify that a complete retry succeeds. Direct
`replaceAll` calls also test duplicate primary/composite keys without preflight.
This suite has no network access or production data.

Supplied JARs for the recorded run: Kotlin compiler 2.1.21 and its POM runtime
dependencies, org.json 20240303, JetBrains annotations 13.0, SQLite JDBC
3.46.1.0, slf4j-api/nop 2.0.13. Downloaded tools are not committed.

## Express API

Set `ORGANIZER_AUDIT_NODE_MODULES` to an isolated install of the dependencies
declared by the web branch's `organizer-sync-server/package.json`, then run:

`node tools/audit/run_server_audit.cjs PATH_TO_SERVER_JS OUTPUT_JSON`

It executes the original Express routes with real Express/bcrypt/JWT modules
and an in-memory `pg` test double. It binds only to loopback. It does not test
real PostgreSQL concurrency, persistence, TLS, Render configuration or backups.
Dependencies and lockfile for the host run remain outside the repository.
