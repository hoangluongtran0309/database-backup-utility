# ADR-006: Compress the dump on the way to disk

**Status**: Accepted
**Date**: 2026-09
**Supersedes part of**: [ADR-005](005-local-filesystem-artifact-storage.md)

## Context

A `mysqldump` of a real schema is SQL text: `INSERT` statements with long runs
of repeated column names and keywords. It compresses by a large factor, and it
is the thing this tool exists to keep a lot of.

[ADR-005](005-local-filesystem-artifact-storage.md) had `mysqldump` write the
file itself with `--result-file`, on the grounds that the dump then never passes
through the JVM. That option cannot coexist with compression: when `mysqldump`
opens the file, nothing else can sit between it and the disk.

## Decision

Drop `--result-file`. The dump goes to `mysqldump`'s stdout, and
`ProcessRunner.runStreaming` copies it into a `GZIPOutputStream` as it arrives.
Artifacts are named `<schema>_<timestamp>.sql.gz`.

Compression is unconditional. There is no setting to turn it off.

## Rationale

**The heap argument in ADR-005 was about buffering, not about passing through.**
Holding a dump in a `String` would be fatal at any real size; copying it through
a fixed buffer is not. `transferTo` moves the bytes in 16 KiB pieces regardless
of how many there are, so memory is constant whether the schema is a megabyte or
a hundred gigabytes. What ADR-005 was actually protecting against still is.

**Why no toggle.** A dump that is worth keeping is worth keeping compressed, and
the alternative to compressing is not "faster" — it is "more disk, and more
bytes to read back at restore". A flag here would be a setting nobody has a
reason to change, and a second code path to keep working.

**Why gzip rather than zstd or xz.** `GZIPInputStream` is in the JDK, so restore
needs no dependency and no external binary, and the artifact opens with `zcat`
on any machine an operator is likely to be sitting at. The better ratios are not
worth an extra runtime requirement on the one path that has to work when
something has already gone wrong.

## Consequences

- The gzip trailer is written when the stream is **closed**, not as bytes flow.
  A file that exists is therefore not necessarily a readable archive, so
  `ProcessRunner` flushes the sink but never closes it — closing belongs to the
  adapter's try-with-resources, and everything that inspects or deletes the file
  happens after that block exits.
- A truncated archive is worse than a truncated plain dump: it fails to
  decompress at all, at exactly the moment someone needs it. Deleting the
  partial file on failure, already the rule in ADR-005, matters more now.
- If the sink cannot be written to — a full disk, most plausibly —
  `ProcessRunner` kills the child immediately. Without that it would sit blocked
  on a pipe nobody is draining until the backup timeout, half an hour later.
- The recorded `size_bytes` is the size of the archive, not of the SQL inside it.
- Restore will have to decompress. That is the slice that adds it.
