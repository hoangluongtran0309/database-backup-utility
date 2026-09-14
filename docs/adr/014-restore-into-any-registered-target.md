# ADR-014: Restore into any registered target

**Status**: Accepted
**Date**: 2026-09
**Supersedes**: one consequence of [ADR-007](007-restore-applies-a-dump-and-asks-first.md) — "Restoring into a *different* target is not possible."
**Refines**: [ADR-008](008-deleting-a-backup-takes-its-history-with-it.md)

## Context

Every restore went back to the target its backup came from. That makes the
one thing an operator should do regularly — prove a backup restores — also the
most dangerous: the only schema a backup could be tried against was the live
one it was taken from.

The dump was already written to allow otherwise. It carries no
`CREATE DATABASE` and no `USE` ([ADR-005](005-local-filesystem-artifact-storage.md)),
so the client loads it into whatever schema it is connected to.

## Decision

A restore can go into **any registered target**. The confirmation page lists
them, preselects the one the backup was taken from, and reloads when another
is chosen. The name to type is the **destination's**.

Each restore records where its data went, in `restore_executions.target_id`
(`V6`). Existing rows are backfilled with their backup's own target, which is
where every restore until now went.

Removing a target removes the records of restores into it. As with a backup's
restores ([ADR-008](008-deleting-a-backup-takes-its-history-with-it.md)), the
use case does this, after the target has been checked for backups; the new
foreign key is `RESTRICT` like every other one.

## Rationale

**Why any target, not only ones marked "scratch".** A marker would be a second
thing to keep correct, and would not make the dangerous case safer: the
confirmation already makes the operator type the name of what is about to be
overwritten. What changes is that that name is now the destination's, so
the check guards the thing it was always meant to.

**Why the page reloads on a choice.** The warning, the address in it and the
name to type all describe one target. Swapping the target underneath them with
a script would risk a page that warns about one schema and overwrites another;
a reload renders every part of it from the choice, and works without the
script.

**Why the destination is stored rather than derived.** Until now the target
of a restore was the backup's target, so it was never stored. With a choice
the two differ, and a history that showed every drill as a restore into
production would be worse than no history.

**Why removing a target takes those records.** The obvious use of this is a
drill target that is registered, restored into, checked and removed. A
`RESTRICT` key with no cleanup would make it impossible to remove without
first deleting the backup that was drilled — the valuable thing. The records
say less once their target is gone; the remove prompt says they go with it.

**Why not `ON DELETE CASCADE`.** For the reason ADR-008 gives: the schema
never removes history by itself, including on a delete typed by hand during an
incident. The use case checks for backups first, so a refused removal takes
nothing with it.

## Consequences

- A restore drill is: register a target pointing at a scratch schema, restore
  into it, check it, remove it. The source is never contacted.
- The destination's credentials are used, and need the rights to create and
  drop the tables in the dump.
- The restore list and detail show where the data went and, separately, which
  target the backup was taken from.
- Restoring into the backup's own target works exactly as before.
- A checksum mismatch ([ADR-013](013-a-checksum-for-every-artifact.md)) is
  checked the same way whichever target is chosen.
