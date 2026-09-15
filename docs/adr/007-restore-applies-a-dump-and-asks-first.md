# ADR-007: Restore applies a dump, and asks before it does

**Status**: Accepted
**Date**: 2026-09
**Related**: [ADR-003](003-shelling-out-to-the-mysql-client.md), [ADR-004](004-persist-the-execution-before-running-it.md), [ADR-006](006-gzip-the-dump-as-it-is-written.md)

## Context

Restoring is the only thing this tool does that destroys data. Everything else
reads a database or writes a file; this overwrites a live schema.

Two questions had to be settled: what "restore" means exactly, and how the
artifact reaches the `mysql` client.

## Decision

**What it means.** A restore feeds the dump to `mysql` against the target's own
schema. Because `mysqldump` emits `DROP TABLE IF EXISTS` before each `CREATE
TABLE`, every table in the backup is replaced. **Tables the dump does not
contain are left exactly as they are.** The schema is not dropped and recreated,
and a restore is not a reset.

The confirmation page says this in as many words, and the operator must type
the target's name to proceed.

**How the bytes get there.** The artifact is decompressed to a temporary file
beside itself, and `ProcessBuilder.redirectInput` points the client's stdin at
that file. The temporary file is deleted in a `finally`. *Superseded by
[ADR-016](016-restore-streams-the-dump-into-the-client.md): the archive is
decompressed as it is fed to the client, and nothing is written to disk.*

A restore is a separate aggregate, `restore_executions`, not a status on the
backup.

## Rationale

**Why not drop and recreate the schema.** It would need `DROP DATABASE`, which a
least-privileged backup user should not have, and it would silently destroy
tables that were never in the backup — a much larger blast radius than anyone
asks for when they click "restore". Applying the dump is the smaller, more
predictable operation. The cost is that it cannot remove a table added since the
backup, which is why the page says so rather than leaving it to be discovered.

**Why typing the name.** A checkbox is a reflex; typing `production` forces the
operator to read which target they are on. The one mistake this feature can make
is restoring into the wrong database, and it is unrecoverable.

**Why a temporary file rather than piping into stdin.** Writing to a child's
stdin from a Java thread adds a fourth stream that must keep moving in lockstep
with the other three. If the child blocks writing output nobody is draining
while we block writing input it is not reading, neither side moves again — the
same deadlock `ProcessRunner` exists to prevent, in a shape that is much harder
to recognise. Letting the kernel redirect a file removes the possibility rather
than managing it. The cost is disk: the decompressed dump, briefly, next to the
archive. It goes beside the artifact rather than in `/tmp` because that
directory is the one already sized for backups, and `/tmp` is often a small
tmpfs. *[ADR-016](016-restore-streams-the-dump-into-the-client.md) revisits
this: with stdout and stderr each drained on a thread of their own, a third
thread feeding stdin shares none of them, and the deadlock cannot form.*

**Why a separate aggregate.** A backup is a thing that exists; restoring it is
an event that can happen many times, or never. Recording it as a status on the
backup would lose the history of every restore but the last.

## Consequences

- `--batch` is passed so the client stops at the first error instead of
  ploughing on and leaving a schema that is part old and part new with nothing
  to show for it. Where it stopped is in the recorded error message.
- A restore that fails partway leaves the schema in a state nobody chose. This
  is inherent to applying a dump statement by statement, and the reason the
  error message is stored and shown verbatim.
- The decompressed copy can be many times the size of the archive. A restore
  needs that much free space in the backup directory. *Superseded by
  [ADR-016](016-restore-streams-the-dump-into-the-client.md): there is no
  decompressed copy.*
- Restores share the job pool with backups, so the bound is on total heavy work
  rather than on each kind separately.
- Removing a backup that has been restored is refused by the foreign key. What
  "should not vanish quietly" means in practice is settled by
  [ADR-008](008-deleting-a-backup-takes-its-history-with-it.md): the deletion
  use case removes the restore records itself, after saying how many there are.
- Restoring into a *different* target is not possible. Every restore goes back
  to the target its backup came from. *Superseded by
  [ADR-014](014-restore-into-any-registered-target.md): a restore can go into
  any registered target, confirmed by typing that target's name.*
