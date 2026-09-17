# ADR-017: Route logical backups and restores by database engine

**Status**: Accepted
**Date**: 2026-09
**Supersedes**: in [ADR-014](014-restore-into-any-registered-target.md), “any registered target” — a destination must use the backup's engine
**Refines**: [ADR-003](003-shelling-out-to-the-mysql-client.md), [ADR-009](009-one-image-that-carries-the-mysql-client.md), [ADR-012](012-editing-a-target-keeps-its-schema.md)

## Context

The first fourteen slices deliberately had one engine. That kept every port
honest: `MysqlLogicalBackupPort` meant exactly one operation, its artifact was
always `.sql.gz`, every target was MySQL without storing the fact, and every
restore destination could consume every backup.

PostgreSQL is the first second engine. Copying the three use cases into
PostgreSQL-specific services would duplicate execution history, encryption,
checksums, storage and background-job behavior. Hiding the difference below
one adapter would be worse: MySQL produces gzipped SQL text, while PostgreSQL's
safe logical artifact for this tool is a custom archive restored by
`pg_restore`.

The application already uses PostgreSQL for its own metadata. That says
nothing about which PostgreSQL servers an operator has authorised as backup
targets; treating the metadata datasource as one implicitly would cross that
boundary and reuse credentials for a purpose they were never given.

## Decision

Every target records a `DatabaseEngine`. `V7` adds the non-null `engine` column
and backfills every existing row as `MYSQL`, because all targets before this
migration were MySQL by definition. The engine and database name are fixed
after registration; a different value is a different target, while name,
host, port, username and password remain editable.

The core ports are engine-neutral:

- `ConnectionTestPort` tests a `DatabaseConnection`;
- `LogicalBackupPort` writes one full logical artifact and declares its suffix;
- `LogicalRestorePort` restores that artifact.

Each implementation declares its `DatabaseEngine`. `EngineAdapterRegistry`
indexes the Spring-provided adapter lists once, rejects duplicate adapters and
gives a use case the adapter for a target's engine. A missing adapter is an
explicit configuration error, never a fallback to another engine.

MySQL keeps its existing behavior and `.sql.gz` artifact. PostgreSQL uses:

- `psql --command=SELECT 1` for its connection probe;
- `pg_dump --format=custom --no-owner --no-privileges` for a `.dump` artifact;
- `pg_restore --clean --if-exists --no-owner --no-privileges --exit-on-error`
  for restore, after `pg_restore --list` has proved the archive readable.

PostgreSQL passwords travel only in the child environment as `PGPASSWORD`.
The configured `psql`, `pg_dump` and `pg_restore` paths are checked for
executability at startup. The base image and CI install those clients beside
the existing MySQL clients.

A backup can be restored only into a target with the same engine. The
application service enforces that before a restore row is written, and the
console only offers compatible destinations. There is no format conversion.

## Rationale

**Why an engine on the target.** The execution points at a target, so its source
format can be recovered without adding the same engine column to every backup
row. It also makes the invariant visible at registration and migration time.

**Why one port per capability, not one giant engine adapter.** Connection
tests, backups and restores are used independently and have different failure
contracts. Keeping the ports narrow preserves the shape that made the MySQL
slice testable, while the registry supplies the common selection rule once.

**Why the artifact suffix belongs to the backup port.** Naming it in the use
case would make `.sql.gz` a hidden MySQL assumption. The adapter owns the
format, so it owns `.sql.gz`, `.dump`, and the future suffixes that arrive with
their own slices.

**Why PostgreSQL custom format.** It is compressed by PostgreSQL, can be listed
before it touches a destination, and is restored by the tool designed for it.
Plain SQL would preserve the MySQL streaming shape only by discarding those
properties.

**Why `--clean` rather than drop the database.** A backup user need not have
permission to create databases, and a destination may be deliberately named
differently for a drill. Objects carried by the archive are replaced; unrelated
objects remain, the same operational caveat the MySQL restore already states.

**Why no engine change on edit.** Changing a MySQL target into PostgreSQL would
make its existing `.sql.gz` history appear to belong to a target that needs
`.dump`, and no adapter could restore it safely. Database names stay fixed for
the same historical reason recorded by ADR-012.

## Consequences

- MySQL targets and artifacts created before V7 keep working unchanged.
- The console shows an engine on targets, backups and restore confirmation;
  selecting an engine supplies its default port, but the port remains editable.
- A PostgreSQL metadata store is not a backup target until an operator registers
  it as one with target credentials.
- Starting the application now requires all five client binaries, even when a
  deployment currently registers only one engine. Optional engine packs are a
  future packaging decision, not part of this slice.
- PostgreSQL client/server format compatibility remains an operator concern.
  This slice configures one `psql`/`pg_dump`/`pg_restore` set, not a matrix of
  versioned clients; source and destination must both be compatible with it.
- Adding an engine means adding its enum value and a complete set of adapters,
  runtime dependencies, integration tests and documentation in that engine's
  slice. Nothing for later engines is declared early.
- Cross-engine restore is refused before any destructive process starts and is
  deliberately outside the roadmap.
