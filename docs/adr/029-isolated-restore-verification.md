# ADR-029: Restore verification uses isolated, disposable databases

## Status

Accepted.

## Context

A checksum proves that an artifact still contains the bytes written by the
backup job. It does not prove that the database engine can restore those bytes
or that the restored objects are readable. Restoring into an operator target
would make that proof destructive, and using the source target's credentials
would couple a recovery test to production access.

Verification must remain evidence about one backup, not a new backup state.
Its failure must therefore never rewrite a successful backup, undo retention,
or become a backup-failure notification.

## Decision

Each attempt is a `RestoreVerificationExecution` belonging to one successful
backup. It moves from `RUNNING` to `SUCCEEDED` or `FAILED`; attempts are kept as
history and may be repeated. PostgreSQL enforces at most one running attempt
per backup with a partial unique index. Deleting the backup cascades its
verification history, while application deletion and retention refuse to
remove an artifact used by a running attempt.

An operator can start an attempt on the backup detail page. A target can also
opt into an attempt immediately after every successful backup. Automatic work
runs on the backup worker after the success is stored and notified and after
retention's existing failure boundary. Manual work uses the same bounded job
executor. Queue rejection is recorded as a failed attempt. Verification
success and failure have their own notification events; there is no started
event.

Before starting an engine, `ArtifactStorageService` materializes Local, S3,
GCS or Azure artifacts into a private path where necessary and verifies the
recorded SHA-256. The isolated adapter never receives the source target's
plaintext credential. Network engines use the Docker CLI, deterministic
container names derived from the verification UUID, no published database
port and these default images:

- MySQL `mysql:8.4`
- MariaDB `mariadb:10.11`
- PostgreSQL `postgres:17-alpine`
- MongoDB `mongo:8.0`

Each image is configurable. SQLite rebuilds one private file below
`SQLITE_ROOT` and does not use Docker. MySQL and MariaDB run `CHECK TABLE` for
every restored base table. PostgreSQL reads every user table. MongoDB runs
`validate` for every non-system collection. SQLite requires
`PRAGMA integrity_check` to return exactly `ok`. An empty database succeeds
when restore and health checks succeed.

Pull, startup, engine and cleanup operations have finite timeouts. Cleanup is
part of the proof: success is stored only after the disposable database has
been removed. On startup, running attempts become failed and cleanup addresses
only the deterministic container name for that verification UUID. Process
interruption preserves the thread interrupt flag.

V19 adds `database_targets.verify_after_backup` and
`restore_verification_executions`. The deployment switch
`DBBACKUP_VERIFICATION_ENABLED` defaults to `false`. The application image
contains the Docker CLI, but Compose deliberately leaves the host socket mount
and socket group commented out. Access to `/var/run/docker.sock` is
root-equivalent control of the Docker host and must be an explicit operator
decision.

## Consequences

- The backup detail page distinguishes byte-level **Verify checksum** from the
  recovery-level **Test restore** and shows every attempt.
- A verification failure, unavailable adapter, timeout or cleanup failure is
  isolated from backup, retention and ordinary restore outcomes.
- Existing notification subscriptions do not change. Newly selected channels
  default to backup, restore and verification failure events.
- Oracle and SQL Server were excluded from this slice; ADR-030 adds them with
  self-contained disposable images without changing this execution model.
- There is no long-lived verification database, published container port,
  verification STARTED event or reuse of source database credentials.
