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

## Deliberately out of scope

Not "later" — absent, and not to be reintroduced without a decision that
supersedes this line:

- MySQL physical backup, XtraBackup, binlog point-in-time recovery
- PostgreSQL and MongoDB as backup engines
- Differential and incremental backups, backup chains, retention cascades
- A scheduler; backups run when asked
- Notification channels; results are read from the execution history
- Cloud storage backends
- A CLI; the web console is the only inbound adapter
