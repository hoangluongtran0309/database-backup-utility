# ADR-035: Ship PostgreSQL 17 clients and reject unsupported source versions

## Status

Accepted.

**Amends** ADR-017 for PostgreSQL client packaging and connection testing.

## Context

Ubuntu 24.04's distribution package supplies PostgreSQL client 16. The base
image therefore reported a PostgreSQL 17 target as connected through `psql`,
then failed its first backup because `pg_dump` refuses a server newer than its
own major version. The integration test hid that mismatch by selecting a
server image from the host's installed `pg_dump` major.

PostgreSQL permits a newer `pg_dump` to read supported older servers, but does
not guarantee that the resulting archive can be loaded into a destination
older than the client. One packaged client major can therefore establish a
clear backup boundary, but cannot promise every cross-major restore.

## Decision

The base image and CI install `postgresql-client-17` from PostgreSQL's official
PGDG repository for Ubuntu Noble. They use and version-check the explicit
`/usr/lib/postgresql/17/bin/psql`, `pg_dump` and `pg_restore` paths instead of
the distribution-managed `/usr/bin` wrappers. The application configuration
names remain `PSQL_PATH`, `PG_DUMP_PATH` and `PG_RESTORE_PATH`; source and
custom-image deployments may still override them.

PostgreSQL connection testing now runs `SHOW server_version_num` through
`psql`, then reads the configured `pg_dump --version`. It succeeds when the
server major is less than or equal to the dump client major and fails before a
backup when the server is newer. An unreadable or unrecognised version is a
failed test, not a successful connection. The failure names both majors, the
configured binary and `PG_DUMP_PATH` as the remedy.

The password remains only in the `psql` child environment. The local
`pg_dump --version` invocation receives no database credentials.

## Consequences

- The shipped image can test, back up and restore PostgreSQL 17 end to end.
- A PostgreSQL server newer than the configured `pg_dump` is rejected by
  **Test** instead of failing after a backup execution starts.
- A client newer than the source remains valid for backup. Operators must
  still choose a `pg_restore` and destination compatible with the archive;
  loading into a server older than the client is not guaranteed.
- The project still packages one PostgreSQL client set, not a matrix or an
  automatic client selector.
- The PostgreSQL integration test is fixed at server 17, and the release-shaped
  E2E image performs a real PostgreSQL 17 backup so package drift cannot make
  the original failure invisible again.
