# ADR-019: SQLite files live below one configured root

## Status

Accepted.

## Context

SQLite has no server endpoint or credentials. Its database is a file, but a
path stored in metadata must not become authority to read or overwrite any file
the application user can reach. A logical restore also must not feed possibly
truncated SQL directly into the live destination.

## Decision

A SQLite target stores one portable relative path, fixed after registration.
`SQLITE_ROOT` is made absolute and canonical at startup. Every Test, backup and
restore resolves the target again, requires an existing readable regular file,
and verifies that its real path remains below the real root. Absolute paths,
`.`/`..` segments, backslashes and symlinks escaping the root are refused.

The `sqlite3` CLI is the only SQLite runtime dependency:

- Test opens the file read-only and accepts only `PRAGMA quick_check` returning
  the single value `ok`.
- Backup first requires a full `PRAGMA integrity_check`, then streams `.dump`
  through gzip to a `.sql.gz` artifact.
- Restore inflates the SQL into a database in an owner-only temporary
  directory with `-bail`, requires a full integrity check returning exactly
  `ok`, and only then uses `.restore` against the destination. The temporary
  database and every journal sidecar are removed afterward.

SQLite restore replaces the complete destination. This differs from the first
three engines, whose restore adapters leave objects absent from the artifact
alone, so the confirmation page states the SQLite behavior explicitly.

## Consequences

- SQLite rows have no host, port, username or encrypted password; the V9 schema
  permits those nulls only for `SQLITE` and keeps them required otherwise.
- The deployment must mount existing files below the root and grant the
  application process enough filesystem permission. This slice does not create
  an application database or change ownership.
- A logical SQL dump preserves schema and content, not byte layout, WAL/SHM
  files, encrypted databases, loadable extensions, `application_id` or
  `user_version`.
- Restore into another SQLite target is supported; cross-engine conversion is
  still refused by the shared restore use case.
