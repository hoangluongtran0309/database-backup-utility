# ADR-020: Oracle Data Pump uses shared staging and an optional client pack

## Status

Accepted.

## Context

Oracle Data Pump is server-side. `expdp` and `impdp` name an Oracle directory
object; the database server, not the client process, reads and writes the dump.
The application must still store artifacts in `BACKUP_DIR`, verify them, and
manage job timeouts. Oracle Instant Client is also distributed under terms and
packaging constraints that do not belong in the base image.

## Decision

Oracle support is an opt-in pack (`ORACLE_ENABLED=false` by default). A target
stores a service name, its login user as the schema being backed up, and one
unquoted directory-object name. The schema and directory are normalized to
uppercase and limited to Oracle's 128-character identifier form. Only the
login user's own schema is exported.

`ORACLE_DATAPUMP_ROOT` is the application's view of storage that is mounted at
the path referenced by the directory object on the Oracle server. Connection
testing uses SQL*Plus and `UTL_FILE` to create a unique probe through that
object, then proves the same file is visible to the application and removes it.

Backup runs schema-mode `expdp` into shared staging with `NOLOGFILE=YES`, then
copies the completed `.dmp` into `BACKUP_DIR`. Restore verifies the recorded
SHA-256 before the adapter receives the artifact, copies it into staging and
compares the copy byte-for-byte. It first runs `impdp SQLFILE`, which parses the
archive and emits DDL without applying it. A successful preflight is followed
by import with `TABLE_EXISTS_ACTION=REPLACE`, `TRANSFORM=OID:N`,
`TRANSFORM=SEGMENT_ATTRIBUTES:N`, and `REMAP_SCHEMA` when the destination login
schema differs.

Every Data Pump job has a deterministic name derived from its execution UUID.
On a timeout or failure, and when startup repairs an execution left `RUNNING`,
the adapter attaches to that name and sends `KILL_JOB` followed by confirmation.
Staging is deleted only after the job is confirmed stopped or Oracle reports
that it no longer exists. Otherwise it is retained for safety and diagnosis.

The base `Dockerfile` and default compose file remain Oracle-free.
`Dockerfile.oracle.example` extends an already-built base image and expects the
operator to provide extracted Instant Client Basic, SQL*Plus and Tools files.

## Consequences

- The directory-object path must be a bind/NFS/shared mount visible at the same
  time to Oracle and the application, and the target user needs `READ, WRITE`
  on that directory object.
- The target schema must already exist. This slice never creates or drops a
  user and does not grant `DATAPUMP_EXP_FULL_DATABASE`.
- Tables represented in the dump are replaced; unrelated objects are kept.
  Restore is not transactional and a failed import may have changed part of
  the destination schema.
- Oracle Wallet, custom TLS, ASM-only staging and cross-engine conversion are
  outside this slice.
- A stored Oracle target remains visible when the pack is disabled, but Test,
  Backup and Restore are rejected before an execution is created.
