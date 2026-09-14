# ADR-013: A checksum for every artifact, checked before restoring

**Status**: Accepted
**Date**: 2026-09

## Context

A backup is only worth what its restore is worth, and until now nothing could
tell whether an artifact was still the file that was written. A truncated
write is already handled — the partial file is deleted and the backup fails
([ADR-006](006-gzip-the-dump-as-it-is-written.md)) — but a file that was
complete and later changed on disk would be found out only by the restore that
needed it, halfway through, with the schema left part old and part new
([ADR-007](007-restore-applies-a-dump-and-asks-first.md)).

## Decision

When a backup succeeds, the SHA-256 of its artifact is recorded with it: of
the file **as stored**, still gzipped, in lower-case hex. It is read back from
disk after the dump has closed the file, not computed from the bytes as they
were written.

The backup's page shows it and offers **Verify**, which reads the file again
and compares.

Every restore checks the artifact first, on the job thread just before the
client starts. A file that is missing or does not match is not applied, and
the target is not contacted.

Backups made before this change have no checksum. They are not backfilled:
they show "Not recorded", Verify reports the checksum the file has now, and a
restore of one proceeds unchecked, as it always did.

## Rationale

**Why the file as stored rather than the SQL inside it.** It is the thing the
operator downloads. `sha256sum shop_20260909_101530.sql.gz` on their machine
prints exactly what the console shows, so a copy kept elsewhere can be checked
against the record without this tool.

**Why read it back rather than hash the stream.** Hashing the bytes on their
way to the gzip stream would certify what the application meant to write. The
point is to describe what the disk holds; reading the closed file is the one
way to know. It also makes one implementation — `StoragePort.sha256Of` — serve
both the recording and every later check, so the two cannot drift apart.

**Why SHA-256 when gzip already has a CRC.** The CRC is over the uncompressed
data and is only checked by decompressing all of it, which a restore does
after it has already started writing. SHA-256 is checked before anything
reaches the target, and it is the value every tool can compute.

**Why not backfill old backups.** A checksum taken today certifies whatever is
on disk today, including a file that has already been damaged. Recording it as
if it had been taken when the backup was made would be claiming something
nobody knows.

**Why check at run time rather than when the restore is accepted.** The file
could change between the two, and the check reads all of it — work that
belongs on the job thread, not in the request.

## Consequences

- A backup that finishes reads its artifact once more before it is marked
  successful. For a large archive that is seconds of disk; a file that cannot
  be read back fails the backup and is deleted.
- Verify is synchronous and reads the whole file. For very large artifacts the
  request takes as long as the disk does.
- A restore of a changed artifact fails with both checksums in its error, and
  the target is untouched.
- `V5` adds the column and a check that only a successful backup carries a
  checksum, in `sha256sum`'s form.
- The checksum proves the file is unchanged, not that it restores. A dump that
  was complete but wrong is still found out only by restoring it.
