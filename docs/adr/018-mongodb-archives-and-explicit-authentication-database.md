# ADR-018: MongoDB archives use an explicit authentication database

**Status**: Accepted
**Date**: 2026-09
**Refines**: [ADR-017](017-route-logical-backups-by-database-engine.md)

## Context

MongoDB is the third backup engine. Its target database and the database that
holds a user's credentials are separate concepts: a user commonly lives in
`admin` while being authorised to read or restore another database. Reusing the
target database implicitly would reject ordinary deployments.

`mongodump` and `mongorestore` can write one compressed archive, but that
archive retains its source namespaces. A restore drill into another database
therefore needs an explicit namespace change. The tools also have no password
environment variable equivalent to `MYSQL_PWD` or `PGPASSWORD`; putting the
password in `--password` or a connection URI would expose it in the process
arguments.

## Decision

`DatabaseTarget` has an optional `authenticationDatabase`. It is required for
MongoDB, absent for MySQL and PostgreSQL, defaults to `admin` in the console and
may be edited because it changes how the same database is reached. `V8` adds
the column and widens the engine constraint without changing existing rows.

MongoDB uses one host and port with username/password SCRAM authentication. The
first slice does not accept a URI, seed list, SRV address, TLS configuration or
external authentication mechanism.

The adapters use MongoDB Database Tools:

- connection testing runs `mongodump` against a random, absent collection and
  writes its small archive header to stdout, exercising the real connection and
  credentials without reading an operator collection;
- backup runs `mongodump --archive=<path> --gzip --db=<database>` and stores an
  `.archive.gz` artifact;
- restore first runs `mongorestore --dryRun`, then applies the archive with
  `--drop --stopOnError`, `--nsFrom=<source>.*` and
  `--nsTo=<destination>.*`.

Each tool invocation receives its password in a unique YAML file created with
owner-only `0600` permissions. Only the file path appears in the arguments.
The file is deleted as soon as that process ends; creating it insecurely or
failing to remove it fails the operation.

The backup contains one database, not its users and roles. `--drop` replaces
collections carried by the archive and leaves unrelated destination
collections alone, matching the restore boundary used by the SQL engines.

## Consequences

- A MongoDB archive can be restored into another registered MongoDB database;
  the application service supplies the source database name to the restore
  port so the adapter can rewrite namespaces.
- The image and CI require `mongodump` and `mongorestore` in addition to the
  five existing SQL client binaries.
- A single-database `mongodump` taken while writes continue is not a
  point-in-time snapshot across collections. Operators that need a consistent
  application-level cut must stop writes themselves. Oplog capture/replay and
  point-in-time recovery remain deliberately outside the roadmap.
- A failed restore may have already replaced some collections; MongoDB does not
  make a multi-collection restore transactional. Its execution record preserves
  the failure instead of claiming success.
