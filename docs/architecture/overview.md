# Architecture overview

A hexagonal application in four Maven modules. The module boundary is the
architectural boundary: crossing it wrongly is a compile error, not a review
comment.

## Modules and dependency direction

```
core        no dependencies at all — plain Java and Lombok
  ↑     ↑
  |     └── adapters      JPA, encryption; everything technical
  |              ↑
  └── application        use cases; depends on core ports only
           ↑     ↑
           └─ web ┘      composition root: Spring MVC + Thymeleaf
```

| Module | Holds | May depend on |
| --- | --- | --- |
| `core` | Domain model, ports, domain exceptions | nothing |
| `adapters` | Outbound adapters — persistence, encryption, Flyway migrations | `core` |
| `application` | Use case orchestration | `core` |
| `web` | HTTP controllers, forms, templates, sign-in and CSRF (`web.security`), `main()` | `application`, `adapters` |

Two consequences are worth stating plainly, because they are the reason for the
split rather than side effects of it:

- **`core` has an empty dependency list.** Not "we avoid importing Spring in
  core" — the jars are not on its classpath, so the import does not compile.
- **`application` does not depend on `adapters`.** Use cases talk to interfaces
  in `core` and are therefore unit-tested with mocked ports, no Spring context
  and no Docker. `web` is the single place that sees both sides, and Spring
  wires them there at startup.

## Where the rules live

- **Business rules are on the domain model.** `DatabaseTarget` validates itself
  in its constructor, so holding a reference to one is already proof that its
  values are sane. Services do not check first.
- **Use case logic is in `application`, never in `web`.** Controllers translate
  HTTP into a use case call and a view name.
- **Who may use the console is decided in `web`, and only there.** Signing in,
  sessions and CSRF tokens are HTTP concerns: `web.security.SecurityConfig`
  guards every request before a controller sees it, and nothing in
  `application` or `core` knows that an operator exists
  ([ADR-011](../adr/011-one-operator-account-from-the-environment.md)).
- **Only `application` calls `EncryptionPort`.** Adapters never hold the key, so
  there is exactly one place in the system where a secret is unwrapped, and one
  place to review. `DatabaseTarget.passwordCiphertext` holds ciphertext at every
  moment of a credential-bearing target's life; SQLite targets have no
  ciphertext. A plaintext password belongs in a separate type named for what it
  carries.

## Metadata PostgreSQL and PostgreSQL targets are separate

This tool stores its own metadata — registered targets and execution history —
in PostgreSQL. That is why the JDBC driver is on the runtime classpath and why
Flyway migrations use PostgreSQL's dialect.

Queries lean on that where it helps: the target list finds each target's newest
backup with `DISTINCT ON`, one row per target however long the history, rather
than reading every backup to find the first of each.

That datasource is not a backup target. A PostgreSQL target is a separate
`DatabaseTarget`, explicitly registered with its own host, database and
credentials. Target access goes through `psql`, `pg_dump` and `pg_restore`, not
through the metadata connection pool. The separation prevents a deployment
credential from silently gaining a second purpose.

## Talking to target databases

Everything that reaches a target runs that engine's client binaries as child
processes, never JDBC — see
[ADR-003](../adr/003-shelling-out-to-the-mysql-client.md) and
[ADR-017](../adr/017-route-logical-backups-by-database-engine.md). Every call
goes through `ProcessRunner`, which is where the subprocess hazards are handled
once:

- **Both pipes are drained concurrently, before `waitFor`.** A pipe holds only a
  few kilobytes; reading stdout to EOF and only then reading stderr deadlocks
  the moment the child fills the other buffer. `ProcessRunnerTest` reproduces
  this with half a megabyte on each stream, and runs on a separate thread so a
  reintroduced deadlock fails the build instead of hanging it.
- **Every command has a timeout**, after which the child is forcibly killed.
- **Input too large for memory is fed from a stream.** `runFeeding` copies a
  caller-supplied source into the child's stdin on a thread of its own, beside
  the two that drain stdout and stderr, so none of the three ever waits on
  another. If the source cannot be read, the child is killed before its stdin
  is closed — a client given a clean end of input would take half a dump for a
  whole one. See [ADR-016](../adr/016-restore-streams-the-dump-into-the-client.md).
- **Every pipe has a dedicated thread**, never one borrowed from the common
  pool, which is one worker smaller than the machine and would run out on a
  small host with two jobs running.
- **Output too large for memory is streamed.** `runStreaming` copies stdout into
  a caller-supplied sink through a fixed buffer, so a dump costs the same heap
  whatever its size. The sink is flushed but never closed — a
  `GZIPOutputStream`'s trailer is written on close, and that belongs to whoever
  opened it. If the sink cannot be written to, the child is killed at once
  rather than left blocked on a pipe until the timeout.
- **Credentials never travel on the command line.** MySQL, MariaDB,
  PostgreSQL and SQL Server's probe use per-child environment variables
  (`MYSQL_PWD`, `PGPASSWORD` and `SQLCMDPASSWORD`). MongoDB
  Database Tools have no equivalent, so each invocation gets a unique YAML
  config file created as `0600` and deleted as soon as the child exits. Nothing
  uses `System.setProperty`, which is JVM-global and would leak between jobs.
  Oracle clients receive the password as the first line of stdin; their argv
  contains only schema, host, port, service name and operation parameters.
  SqlPackage receives its password from a UTF-8 response file created as
  `0600`; that file is removed on every exit and its secret never enters argv.

`MysqlClient` adds the MySQL-specific knowledge: it rewrites the literal host
`localhost` to `127.0.0.1`, because the client otherwise connects over a Unix
socket and silently ignores `--port`. `MysqlDumpBackupAdapter` reuses that rule
but not the connect timeout — `mysqldump` does not accept `--connect-timeout`
and exits 7 with *unknown variable* if given it, so the `ProcessRunner` timeout
is its only backstop.

MariaDB has a separate `MariaDbClient`, dump adapter and restore adapter; no
MariaDB target is routed through MySQL compatibility. Every invocation forces
TCP so `localhost` cannot select a Unix socket and ignore the registered port.
`mariadb-dump` streams one positional database through gzip with routines,
triggers and events, without MySQL's `--set-gtid-purged`. Restore reads the
archive to EOF before streaming it into `mariadb`. See
[ADR-021](../adr/021-mariadb-uses-its-own-client-tools.md).

The PostgreSQL adapters pass the host, port, user and database explicitly and
set `PGCONNECT_TIMEOUT`. `PostgresDumpBackupAdapter` writes a custom-format
archive directly to its destination; `PostgresRestoreAdapter` lists that
archive before starting a destructive restore. Each configured binary is
checked for executability when its adapter is constructed.

The MongoDB adapters pass an explicit authentication database separately from
the database being backed up. `mongodump` writes a gzip-compressed archive;
`mongorestore` dry-runs it before applying it and rewrites the source namespace
when the destination database has another name. See
[ADR-018](../adr/018-mongodb-archives-and-explicit-authentication-database.md).

SQLite is file-based rather than networked. Its registered relative path is
resolved to a real file below `SQLITE_ROOT` for every operation, including a
real-path check that rejects escaping symlinks. Backup streams `.dump` through
gzip. Restore loads the SQL into an owner-only temporary database, accepts only
an exact `ok` from `PRAGMA integrity_check`, then uses SQLite's `.restore` to
replace the destination. See
[ADR-019](../adr/019-sqlite-files-below-one-root.md).

Oracle is an optional, all-or-nothing adapter pack. SQL*Plus probes the login,
directory grant and shared mount with `UTL_FILE`. Data Pump exports the login
schema to shared staging and copies a completed `.dmp` into artifact storage.
Restore stages and byte-compares the artifact, uses `impdp SQLFILE` as a
non-mutating preflight, then imports with replacement, portable transforms and
schema remapping. Stable job names derived from execution UUIDs let timeout
handling and startup repair attach and issue `KILL_JOB`; staging is retained
when termination cannot be confirmed. See
[ADR-020](../adr/020-oracle-data-pump-shared-staging-and-optional-client-pack.md).

SQL Server is another optional, all-or-nothing pack. `sqlcmd` runs `SELECT 1`
over an encrypted connection. SqlPackage exports a validated `.bacpac` and
imports only into a missing or object-free database; the adapter never drops a
database. Each operation gets a private staging directory below
`SQLSERVER_TEMP_DIR`, removed together with partial artifacts on failure or
timeout. See
[ADR-022](../adr/022-sql-server-bacpac-and-optional-client-pack.md).

The use cases do not know those commands. `DatabaseConnection` carries the
short-lived plaintext credential and `EngineAdapterRegistry` selects a
`ConnectionTestPort`, `LogicalBackupPort` or `LogicalRestorePort` by
`DatabaseEngine`. Duplicate adapters fail startup; an engine is published only
when its test, backup and restore adapters are all present, and a partial set
fails startup. The restore use case rejects different source and
destination engines before it persists a job.

## Running a backup

A dump takes minutes, so it does not run on the request thread. The two halves
are separate on purpose — see
[ADR-004](../adr/004-persist-the-execution-before-running-it.md):

1. **Accepting.** `RunBackupService.start` writes a RUNNING `backup_executions`
   row, commits it, submits the job to a bounded pool and returns its id. The
   controller redirects to `/executions/{id}`. This method is deliberately not
   `@Transactional`: the row has to be visible to the background thread, which
   reads it through a different connection.
2. **Running.** The pooled thread decrypts the password, selects the engine's
   backup adapter, asks it for the artifact suffix, asks `StoragePort` where the
   artifact goes, and writes the outcome onto the same row. MySQL and MariaDB
   each stream their own gzipped SQL to `<database>_<timestamp>.sql.gz`;
   PostgreSQL writes a custom
   archive to `<database>_<timestamp>.dump`; MongoDB writes a compressed archive
   to `<database>_<timestamp>.archive.gz`; SQLite streams gzipped SQL to
   `<file>_<timestamp>.sql.gz` without decrypting a credential; SQL Server
   writes a BACPAC to `<database>_<timestamp>.bacpac`.

Meanwhile the detail page follows the row: it re-fetches itself every two
seconds and swaps in the part that changed, until the row reaches a finished
state — see [ADR-010](../adr/010-the-detail-page-follows-a-running-job.md).

The pool is bounded on both axes. A full queue is refused and recorded as a
failed execution rather than growing without limit, because every running
backup is a child process competing for the same disk.

A restore follows the same two-step shape and shares the same pool, so the bound
is on total heavy work rather than on each kind separately. What a restore
actually does — and what it deliberately does not — is in
[ADR-007](../adr/007-restore-applies-a-dump-and-asks-first.md). It can go into
any registered target of the same engine
([ADR-017](../adr/017-route-logical-backups-by-database-engine.md)), and before
the client starts, the job thread checks the artifact against the checksum
recorded when it was written
([ADR-013](../adr/013-a-checksum-for-every-artifact.md)).

Any row still RUNNING when the application starts is repaired and marked
failed. Local child processes are gone with the previous application process;
an Oracle Data Pump server job can survive it, so its adapter first attaches by
the stable execution-derived job name and sends `KILL_JOB`.

## Removing things

Nothing is removed automatically. The chain is strict and walked by the use
cases, never by the schema: a target cannot go while it has backups, and a
backup cannot go while restore records refer to it. Deleting a backup removes
those records, then its file, then its row — in that order, so a failure never
strands a file on disk with nothing pointing at it. See
[ADR-008](../adr/008-deleting-a-backup-takes-its-history-with-it.md). Removing
a target likewise removes the records of restores into it
([ADR-014](../adr/014-restore-into-any-registered-target.md)).

Several backups go together by the same steps, one after another, and a target
can take its backups with it once its name is typed. Every check — a backup
still running, a restore still reading one — is made for all of them before any
is touched
([ADR-015](../adr/015-deleting-many-backups-and-a-target-with-them.md)). Every
foreign key is `RESTRICT`: the schema never removes history by itself.

`StoragePort` refuses to read or delete anything outside its configured root.
Every path it receives was read back from the database, and a value in a
database is not a reason to trust it.

## Testing

`*Test.java` is a plain JUnit test run by Surefire in `mvn test`. `*IT.java` is
a Testcontainers test run by Failsafe in `mvn verify`. H2 is not used anywhere —
the repository tests depend on a functional unique index, PostgreSQL's own
constraint-violation message, and Hibernate schema validation against the real
Flyway output, and H2 would misreport all three.

No test may skip itself because something it needs is absent. A test that turns
green by not running is worse than no test at all — so the integration tests
assert that all MySQL, MariaDB, PostgreSQL, MongoDB, SQLite and SQL Server client binaries
are present rather than assuming it, and CI installs them explicitly. The Oracle
Free test instead creates executable host wrappers that invoke the real
`sqlplus`, `expdp` and `impdp` inside its Oracle container, keeping Oracle
client packages off the runner.

## Database migrations

Flyway migrations live in `adapters/src/main/resources/db/migration/`, named
`V<n>__snake_case_description.sql`. Once a migration has been applied its SQL is
never edited; a correction is a new migration. Hibernate runs with
`ddl-auto: validate`, so a mapping that drifts from the schema fails at startup
rather than in production.

`V7` is the boundary between the single-engine history and the engine-routed
model. It adds `database_targets.engine`, backfills every pre-existing row as
`MYSQL`, then makes the column non-null. Its integration test migrates a real
database only to V6, inserts a target with backup and restore history, applies
V7 and proves the ciphertext and history survived.

`V8` adds MongoDB's nullable `authentication_database`, keeps it absent for
existing SQL targets, and requires it for new MongoDB rows while widening the
engine constraint to include `MONGODB`.

`V9` adds `SQLITE`, widens `database_name` for relative file paths, and makes
network and credential columns nullable only for SQLite rows. A database
constraint preserves the old required connection shape for every other engine.

`V10` adds `ORACLE`, widens `username` to 128 characters, and adds
`data_pump_directory`. The latter is required only for Oracle rows and must be
null for every other engine.

`V11` adds `MARIADB` to the engine constraint. MariaDB uses the existing
network connection shape and the 128-character username column, so no new
column or data rewrite is needed.

`V12` adds `SQLSERVER` to the engine constraint. It reuses the network
connection shape and existing 128-character columns and does not rewrite any
stored target.
