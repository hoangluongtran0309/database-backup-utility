# ADR-025: S3 storage profiles use local staging

## Status

Accepted.

**Supersedes** ADR-005's decision that every durable artifact is a local path.
The built-in local filesystem remains supported and remains the default.

## Context

A host volume survives a container but not a lost host. Operators need an
object-store destination without giving database engines object-store concerns
or changing dump formats. Engine ports write and restore from `Path`, while
download, verification, deletion and retention must use the provider that owns
each historical artifact.

## Decision

An S3-compatible `StorageProfile` stores endpoint, region, existing bucket,
prefix, path-style choice and either encrypted static credentials or AWS's
default credential chain. Profile names are case-insensitively unique. The
built-in local destination has no row and cannot be edited or deleted.

A target selects a nullable profile id. `BackupExecution` snapshots that id
when accepted and stores an `ArtifactReference`: a local absolute path when the
id is null, otherwise `<prefix>/<target-id>/<execution-id>/<filename>`.
Changing a target affects only future executions. Failed executions clear the
snapshot because they own no artifact.

`ArtifactStorageService` is the single application router. S3 backups dump to
an execution-private directory below `STORAGE_STAGING_DIR`, calculate size and
SHA-256, complete multipart upload, and only then become successful. A metadata
failure best-effort deletes the completed object. S3 restore downloads to
private staging and verifies SHA-256 before the engine starts. Staging is
removed after success or failure. Download and verify read S3 as streams;
manual deletion and retention route through the stored reference.

The connection test writes a unique probe, heads it, gets and byte-compares it,
then deletes it. Endpoint, region, bucket, prefix and path style become
immutable after an execution references the profile. Rename and credential
rotation remain allowed. Deletion is restricted while a target or artifact
references the profile.

## Consequences

- The bucket must exist; the application never creates it.
- Production endpoints should use HTTPS. This slice has no custom CA or
  insecure-TLS switch.
- The S3 principal needs Put/Get/Head/Delete and multipart abort permissions.
  A bucket lifecycle should abort multipart uploads abandoned by process crash.
- Staging capacity must cover one complete artifact for every concurrent S3
  job. A durable second local copy is deliberately not retained.
- GCS, Azure, presigned URLs, browser-direct transfer, server-side-encryption
  configuration and object-version cleanup require later decisions.
