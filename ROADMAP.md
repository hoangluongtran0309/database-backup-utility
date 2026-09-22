# Roadmap

One vertical slice at a time: domain, adapter, use case, web, migration, tests
and documentation land together in a single commit. A slice is not started
until the previous one is finished, and nothing is built ahead of the slice
that needs it.

- [x] **1. Register and list MySQL targets.** Immutable `DatabaseTarget`,
      JPA persistence, AES-256-GCM encryption of the stored password,
      registration form and list page.
- [x] **2. Test connection to a target.** `mysql --execute="SELECT 1"` through
      a shared `ProcessRunner`; the result and MySQL's own error text are
      stored and shown.
- [x] **3. Run a full logical backup.** `mysqldump` to local disk, execution
      history, started in the background so a long dump does not hold an HTTP
      request open.
- [x] **4. Compress the dump.** Stream `mysqldump` output through gzip.
- [x] **5. Restore a backup into a target.** With an explicit overwrite
      confirmation, and a round-trip integration test.
- [x] **6. Download and delete artifacts.** Deleting an execution deletes its
      file, which is also what makes a used target removable.
- [x] **7. Package.** One Dockerfile, one compose file, quickstart.

That was the first round. One more slice went in before it was released:

- [x] **8. Sign in.** One operator account from the environment (bcrypt hash),
      session sign-in, CSRF tokens on every form, security headers; the
      healthcheck moves to `/actuator/health`. See ADR-011.

All eight shipped as **0.1.0** (tag `v0.1.0`).

## Second round

Gaps the first round's own ADRs recorded, closed without widening the scope
below.

- [x] **9. Edit a target.** Name, host, port, user and password; the schema is
      fixed, and an empty password keeps the stored one. See ADR-012.
- [x] **10. Checksum every artifact.** A SHA-256 recorded when the dump is
      written, shown on the backup, and checked before a restore. See ADR-013.
- [x] **11. Restore into another target.** Any registered target, confirmed by
      typing its name — for restore drills that do not overwrite production.
      See ADR-014.
- [x] **12. Paginate the history.** Backup and restore lists a page at a time,
      and the target list without reading every backup ever made.

All four shipped as **0.2.0** (tag `v0.2.0`).

## Third round

The disk the backups live on, which nothing but the operator keeps in check.

- [x] **13. Delete backups in bulk.** Tick several on the backup list and
      delete them together; remove a target with all its backups by typing its
      name. Still by hand — nothing prunes automatically. See ADR-015.
- [x] **14. Stream the restore.** Feed the gunzipped dump straight into the
      client instead of through a decompressed copy on disk, so a restore no
      longer needs free space many times the archive's size. The archive is
      read through once first, so a truncated one still never reaches the
      target. See ADR-016.

Both slices shipped as **0.3.0** (tag `v0.3.0`).

## Fourth round

Break the single-engine boundary without widening what a backup means: each
engine gets one full logical dump and a restore into that same engine.

- [x] **15. Multi-engine foundation and PostgreSQL logical backup/restore.**
      Route connection tests, backups and restores by engine; migrate every
      existing target to `MYSQL`; add PostgreSQL through `psql`, custom-format
      `pg_dump` and `pg_restore`; expose the choice in the console and package
      the PostgreSQL clients. See ADR-017.

## Committed engine sequence

Only the engine in the active slice is declared in code. Each item below must
land as its own complete vertical slice — runtime, tests and documentation
included — before the next one starts.

- [x] **16. MongoDB logical backup/restore.** Gzipped archive through
      `mongodump` and `mongorestore`, including namespace changes for a restore
      into another MongoDB database. See ADR-018.
- [x] **17. SQLite logical backup/restore.** Files already mounted below
      `SQLITE_ROOT`; a gzipped SQL dump, restored through a temporary file and
      accepted only after `PRAGMA integrity_check`. See ADR-019.
- [x] **18. Oracle logical backup/restore.** Schema-level Data Pump through an
      Oracle directory object and a shared directory; the client belongs in an
      operator-provided image variant, not the base image. See ADR-020.
- [x] **19. MariaDB logical backup/restore.** Its own `mariadb-dump` and
      `mariadb` adapter rather than assuming MySQL dump compatibility. See
      ADR-021.
- [x] **20. SQL Server logical backup/restore.** BACPAC export/import through
      SqlPackage, supplied by an image variant or the operator. See ADR-022.

## Deferred after engine coverage

The first post-engine item is now complete. The remaining items stay ordered,
but are not numbered as slices until the item before them is complete and
their design is decided.

- [x] **21. Backup schedules with Quartz.** Persist named target/cron/time-zone
      definitions, create and pause them in the console, rebuild Quartz
      triggers at startup, and route every fire through the ordinary backup
      execution path. See ADR-023.

1. [ ] Automatic retention.
2. [ ] Storage profiles and S3-compatible storage, then GCS and Azure.
3. [ ] Notifications.
4. [ ] Restore verification in a temporary database.
5. [ ] A CLI inbound adapter.
6. [ ] CI/CD, security scanning and end-to-end hardening.

## Deliberately out of scope

Not "later" — absent, and not to be reintroduced without a decision that
supersedes this line:

- Physical backups for any engine
- Differential or incremental backups, and backup chains
- Binlog, WAL or oplog replay, and point-in-time recovery
- Conversion or restore between different database engines

SSH tunnels, IAM authentication, Oracle Wallets and custom TLS configuration
are not part of the first engine slices. They may be considered in a later
hardening round; none is implied by the engine checklist above.
