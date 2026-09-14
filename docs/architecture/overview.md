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
  moment of its life; a plaintext password belongs in a separate type named for
  what it carries.

## PostgreSQL is the metadata store, not a backup engine

This tool stores its own metadata — registered targets, and later the execution
history — in PostgreSQL. That is the only reason `org.postgresql` appears in the
build and the only reason the Flyway migrations are written in PostgreSQL's
dialect.

**PostgreSQL is not a database this tool can back up.** There is no PostgreSQL
engine adapter, no `POSTGRESQL` enum value, and no plan for one in the current
roadmap. MySQL is the only engine, which is also why `database_targets` has no
`engine` column: the migration that introduces a second engine is the one that
should add it.

## Talking to MySQL

Everything that reaches a target does so by running the MySQL client binaries as
child processes, never over JDBC — see
[ADR-003](../adr/003-shelling-out-to-the-mysql-client.md). Every such call goes
through `ProcessRunner`, which is where three subprocess hazards are handled
once:

- **Both pipes are drained concurrently, before `waitFor`.** A pipe holds only a
  few kilobytes; reading stdout to EOF and only then reading stderr deadlocks
  the moment the child fills the other buffer. `ProcessRunnerTest` reproduces
  this with half a megabyte on each stream, and runs on a separate thread so a
  reintroduced deadlock fails the build instead of hanging it.
- **Every command has a timeout**, after which the child is forcibly killed.
- **Input too large for memory is redirected from a file.** `runWithInput`
  points the child's stdin at a file and lets the kernel do the feeding.
  Writing to a child's stdin from a thread of ours would add a fourth stream to
  keep in lockstep, and a writer blocked on a child that is itself blocked
  writing output is the same deadlock in a harder-to-see shape.
- **Output too large for memory is streamed.** `runStreaming` copies stdout into
  a caller-supplied sink through a fixed buffer, so a dump costs the same heap
  whatever its size. The sink is flushed but never closed — a
  `GZIPOutputStream`'s trailer is written on close, and that belongs to whoever
  opened it. If the sink cannot be written to, the child is killed at once
  rather than left blocked on a pipe until the timeout.
- **Credentials travel in `ProcessBuilder.environment()`** as `MYSQL_PWD`. Never
  on the command line, where `ps` shows them to every user on the host, and
  never through `System.setProperty`, which is JVM-global and would leak between
  jobs running at the same time.

`MysqlClient` adds the MySQL-specific knowledge: it rewrites the literal host
`localhost` to `127.0.0.1`, because the client otherwise connects over a Unix
socket and silently ignores `--port`. `MysqlDumpBackupAdapter` reuses that rule
but not the connect timeout — `mysqldump` does not accept `--connect-timeout`
and exits 7 with *unknown variable* if given it, so the `ProcessRunner` timeout
is its only backstop.

## Running a backup

A dump takes minutes, so it does not run on the request thread. The two halves
are separate on purpose — see
[ADR-004](../adr/004-persist-the-execution-before-running-it.md):

1. **Accepting.** `RunBackupService.start` writes a RUNNING `backup_executions`
   row, commits it, submits the job to a bounded pool and returns its id. The
   controller redirects to `/executions/{id}`. This method is deliberately not
   `@Transactional`: the row has to be visible to the background thread, which
   reads it through a different connection.
2. **Running.** The pooled thread decrypts the password, asks `StoragePort`
   where the artifact goes, runs `mysqldump` with its stdout gzipped straight
   to that file, and writes the outcome onto the same row. Artifacts are
   `<schema>_<timestamp>.sql.gz` — see
   [ADR-006](../adr/006-gzip-the-dump-as-it-is-written.md).

Meanwhile the detail page follows the row: it re-fetches itself every two
seconds and swaps in the part that changed, until the row reaches a finished
state — see [ADR-010](../adr/010-the-detail-page-follows-a-running-job.md).

The pool is bounded on both axes. A full queue is refused and recorded as a
failed execution rather than growing without limit, because every running
backup is a child process competing for the same disk.

A restore follows the same two-step shape and shares the same pool, so the bound
is on total heavy work rather than on each kind separately. What a restore
actually does — and what it deliberately does not — is in
[ADR-007](../adr/007-restore-applies-a-dump-and-asks-first.md).

Any row still RUNNING when the application starts belongs to a process that is
gone — jobs run here and nowhere else — so startup marks them failed.

## Removing things

Nothing is removed automatically. The chain is strict and walked by hand:
a target cannot go while it has backups, and a backup cannot go while restore
records refer to it. Deleting a backup removes those records, then its file,
then its row — in that order, so a failure never strands a file on disk with
nothing pointing at it. See
[ADR-008](../adr/008-deleting-a-backup-takes-its-history-with-it.md).

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
assert that the `mysql` binary is present rather than assuming it, and CI
installs it explicitly.

## Database migrations

Flyway migrations live in `adapters/src/main/resources/db/migration/`, named
`V<n>__snake_case_description.sql`. Once a migration has been applied its SQL is
never edited; a correction is a new migration. Hibernate runs with
`ddl-auto: validate`, so a mapping that drifts from the schema fails at startup
rather than in production.
