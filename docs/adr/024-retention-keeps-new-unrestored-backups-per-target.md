# ADR-024: Retention keeps new unrestored backups per target

## Status

Accepted.

**Supersedes** the earlier decisions in ADR-008, ADR-015 and ADR-023 to leave
all pruning manual.

## Context

Schedules can now create artifacts indefinitely. Manual bulk deletion can
recover space, but it still makes the operator notice growth and choose rows.
Automatic deletion must be predictable, must not make a failed backup reduce
the set of good copies, and must not silently erase evidence that an artifact
was used in a restore.

Age-based cleanup depends on schedule frequency and can delete every copy after
a quiet period. A policy attached to a schedule would not cover manual backups,
and one global value cannot express that targets have different importance.

## Decision

Retention is optional and configured per target as **keep the newest N
successful, unprotected backups**. No policy row means disabled. A policy is
applied only after another backup of that target has been stored successfully;
saving a policy, startup and failed backup attempts never prune anything.

Any backup with one or more restore records is protected forever from automatic
retention, regardless of whether the restore succeeded. Protected backups are
excluded before N is counted, so they do not crowd out recent rotating copies.
Failed and running backup attempts are historical records and are never
automatic candidates.

Candidates use the history's stable ordering: `started_at DESC, id DESC`.
Retention deletes the artifact and then its backup row, but unlike an
operator-confirmed deletion it never deletes restore records. Restore
acceptance and retention deletion share an in-process lock keyed by backup id;
a restore accepted first creates its row and protects the backup, while a
retention deletion completed first makes the restore fail before a job exists.

The policy row stores the latest run time, deleted count and error. A partial
failure stops the sweep, records how many went first, and leaves the remaining
rows visible for the next successful backup to retry. A retention failure never
changes the new backup from `SUCCEEDED` to `FAILED`. Result updates are
conditional on the policy's configuration timestamp, so an old sweep cannot
recreate a disabled policy or overwrite the status of one edited meanwhile.

## Consequences

- Existing targets begin with retention disabled; enabling it does not delete
  anything until the next successful backup.
- Restore history can make the number of retained artifacts exceed N. The
  operator can still use the existing confirmed deletion flow when that history
  and artifact should deliberately go together.
- The policy foreign key uses `ON DELETE CASCADE`: policy metadata has no value
  without its target and contains no artifact or operational history. Backup
  and restore foreign keys remain restrictive and their deletion stays in use
  cases.
- Cleanup runs on the backup worker after success. It does not add a second
  Quartz schedule, replay missed cleanup, or prune a target that no longer gets
  backups.
- The backup-id lock is process-local. This remains a single-active-instance
  deployment, as scheduling already requires; multi-instance retention and
  restore coordination require a later database lock or leader decision.
