# ADR-005: Artifacts on the local filesystem, written by mysqldump itself

**Status**: Accepted
**Date**: 2026-09
**Related**: [ADR-003](003-shelling-out-to-the-mysql-client.md), [ADR-004](004-persist-the-execution-before-running-it.md)

## Context

A dump has to be written somewhere, and something has to decide where and under
what name.

## Decision

One directory on the local filesystem, behind `StoragePort`, configured by
`dbbackup.storage.local.root` and created and checked for writability at
startup.

`mysqldump --result-file=<path>` writes the file itself. The JVM never sees the
dump's bytes.

Artifacts are named `<schema>_<yyyyMMdd_HHmmss UTC>.sql`, with the schema name
sanitised to `[a-zA-Z0-9._-]`.

A failed dump deletes its own partial file before reporting the failure.

## Rationale

**Why `--result-file` rather than reading stdout.** Streaming the dump through
the JVM would put a multi-gigabyte schema through the heap for no benefit, and
add a second place where a copy could be truncated. stderr is still drained
concurrently by `ProcessRunner`, which is what keeps a chatty warning from
filling its pipe and stalling the child.

**Why the partial file is deleted.** A truncated dump is worse than no dump at
all: it is the right size to look plausible and restores as silent, partial data
loss. The only safe state after a failure is no file.

**Why the name is sanitised.** It is derived from the target's schema name,
which someone typed into a form. A schema called `../../etc` must not be able to
choose where a file lands. `StoragePort.locationFor` rejects anything containing
a separator as well, so the guarantee does not rest on the caller remembering to
sanitise, and `delete` refuses any path outside the root — the path it is given
was read back from the database, and a value in a database is not a reason to
trust it.

**Why one port with one implementation.** `StoragePort` earns its place at the
edge of the hexagon: it is what lets `RunBackupService` be unit-tested with no
filesystem. It is not there in anticipation of S3.

## Consequences

- Backups live on the machine that runs the application. Getting them somewhere
  durable is the operator's job, by whatever means already backs up that host.
- The default root is `./backups`, which is git-ignored. A deployment should set
  `BACKUP_DIR` to something outside the working directory.
- Nothing deletes artifacts yet, so the directory grows without limit. Removing
  a target that has backups is refused rather than allowed to strand files; the
  ability to delete them arrives with the slice that adds it.
- The dump is written with the schema name positional rather than via
  `--databases`, so it contains no `CREATE DATABASE` or `USE`. That is what will
  let a restore load it into a schema with a different name.
