# ADR-008: Deleting a backup takes its file and its restore history with it

**Status**: Accepted
**Date**: 2026-09
**Refines**: [ADR-007](007-restore-applies-a-dump-and-asks-first.md)

## Context

Until now nothing removed anything. Artifacts accumulated, and because a target
with backups cannot be removed ([ADR-005](005-local-filesystem-artifact-storage.md)),
a target registered by mistake was permanent.

Two foreign keys stand in the way of deleting a backup, both `ON DELETE
RESTRICT` and both put there deliberately:

- `backup_executions → database_targets`, so a target cannot be removed while
  its backups exist.
- `restore_executions → backup_executions`, so — in ADR-007's words —
  "removing a backup must not silently erase the record that it was once
  restored somewhere".

The second one now blocks the very operation this slice exists to add.

## Decision

Deleting a backup removes, in this order: its restore records, its artifact
file, then its row.

The foreign keys stay `RESTRICT`. The cascade is written in the use case, not in
the schema, and the confirmation page states how many restore records will go.

Deletion is a two-page flow — a confirmation page, then a POST — but does not
require typing anything.

Nothing deletes automatically. There is no retention policy.

## Rationale

**ADR-007's reasoning was right about "silently" and wrong about "never".** A
restore record whose backup no longer exists says almost nothing: it cannot show
what was restored or from when. The value it had was in being connected to the
backup. What actually mattered was that the record should not disappear
*without the operator knowing*, and a confirmation page that counts them out
loud satisfies that better than a constraint that makes cleanup impossible.

**Why the cascade is in the use case rather than the schema.** `ON DELETE
CASCADE` would make this happen on any delete, including one issued by hand
against the database during an incident. Keeping the foreign key strict means
the database still refuses to orphan anything, and the only path that removes
history is the one that told a human first.

**Why file before row.** The reverse order — row first — would, on a failure to
delete the file, leave an artifact on disk with nothing in the database pointing
at it: invisible, and findable later only by hand. This order fails the other
way, leaving a visible row whose file is already gone, which the operator can
simply delete again.

**Why no typed confirmation, unlike restore.** Restoring overwrites a live
database and cannot be undone in any sense. Deleting a backup destroys a copy,
and the original is still there. The confirmation page is warranted; making
someone type a name for routine cleanup would train them to type it without
reading.

**Why no retention policy.** Automatic deletion of backups is the kind of
feature that works perfectly until the day it deletes the one you needed. It is
not in this round's scope, and adding it would mean deciding what "keep the last
N" means for failed backups, interrupted ones, and backups that have been
restored — questions with no obvious answers yet.

## Consequences

- A target can now be removed, once its backups are deleted one at a time. That
  is tedious with many backups; bulk deletion is not part of this round.
- A backup that is still running cannot be deleted — the dump is being written
  to that file.
- Downloading serves the artifact exactly as stored, still gzipped, so what
  lands on the operator's machine is byte for byte what this tool would restore.
- `StoragePort` refuses to read or delete anything outside its root. Every path
  it is given was read back from the database, and a hand-edited row should not
  be enough to reach an arbitrary file.
- The backup directory still grows until somebody prunes it.
