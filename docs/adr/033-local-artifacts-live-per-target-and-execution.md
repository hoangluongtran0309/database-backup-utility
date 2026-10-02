# ADR-033: Local artifacts live per target and execution

## Status

Accepted.

**Amends** ADR-005 and ADR-025 for the built-in local destination. Remote
object keys are unchanged.

## Context

Local artifacts were written directly into `BACKUP_DIR` as
`<database>_<yyyyMMdd_HHmmss><suffix>`. The name identifies neither the target
nor the execution, and its resolution is one second. The engine adapters open
the destination with the default `CREATE, TRUNCATE_EXISTING` options.

The 0.19.0 walkthrough ([ISSUE-01](../walkthrough/ISSUES.md#issue-01)) showed
what follows:

- A MySQL and a MariaDB target, both on database `shop` and backed up in the
  same second, wrote one file. The second dump truncated the first. The first
  execution stayed *Succeeded*, but its checksum no longer matched and its
  backup was lost.
- Deleting either execution deleted the other one's file.
- Two quick **Back up now** presses on one target did the same.
- The startup repair of interrupted backups, and the cleanup after an
  unexpected failure, recompute the planned path and delete it. Under the flat
  layout that could be another backup's completed file.

Remote profiles never had this problem. Their keys are already
`<prefix>/<target-id>/<execution-id>/<filename>`.

## Decision

A new local artifact is written to
`BACKUP_DIR/<target-id>/<execution-id>/<filename>`.
`StoragePort.locationFor(targetId, executionId, filename)` creates the two
directories. The file name keeps its readable `<database>_<timestamp>` form, so
downloads and the documented names do not change.

Before an engine starts, `RunBackupService` refuses a local destination that
already exists, failing the backup with "Refusing to overwrite an existing
artifact". It raises a `BackupFailedException`, which does not clean up local
files, so the refusal can never delete the file it found.

Deleting a local artifact also removes the execution and target directories it
leaves empty. `BACKUP_DIR` itself is never removed, and a directory that still
holds anything is left alone.

The stored locator remains the absolute path. Artifacts written before this
decision keep their flat locators and are still read, verified, restored and
deleted in place, because every path is checked only to be inside
`BACKUP_DIR`. Nothing is migrated.

## Consequences

- Two executions can no longer share a local file, whatever their database
  names, engines or start times. That includes Oracle schemas in one service
  ([ISSUE-05](../walkthrough/ISSUES.md#issue-05)).
- `BACKUP_DIR` holds one directory per target that has local backups, rather
  than a flat list of files. Operators who browse or copy it directly should
  expect the nesting.
- A backup interrupted under the old layout, and repaired after upgrading,
  leaves its partial flat file behind. It can be deleted by hand.
