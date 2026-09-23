# ADR-027: Azure Blob Storage uses native block blobs and Azure credentials

## Status

Accepted.

**Extends** ADR-025's provider-neutral artifact reference and local staging
workflow. Existing local, S3 and GCS artifacts are unchanged.

## Context

Operators on Azure need a native destination that can use workload or managed
identity without storing a long-lived secret. Installations outside Azure and
the Azurite emulator still need key-based authentication. Database engines
must remain unaware of object storage and continue to read and write a local
`Path`.

## Decision

`StorageProfile` adds Azure Blob Storage as an immutable provider. An Azure
profile stores an account name, existing container, blob prefix, optional
service endpoint and either Azure Default Credential or an AES-256-GCM-encrypted
storage-account key. Account names contain 3–24 lower-case letters or digits.
The plaintext key exists only in a short-lived `AzureBlobStorageConnection`,
never appears in its string representation and never returns to the browser.

An empty endpoint derives `https://<account>.blob.core.windows.net`. An
explicit HTTPS endpoint supports regional deployments; HTTP is exposed only
for development emulators such as Azurite. Azure Default Credential obtains
Microsoft Entra credentials from the deployment environment, including
workload identity, managed identity or service-principal environment values.

The native Azure Java SDK uploads the staged file as a block blob. Downloads
and checksum verification stream from the blob; restore materializes it in an
operation-private staging directory. Blob names keep ADR-025's
`<prefix>/<target-id>/<execution-id>/<sanitized-filename>` layout. Upload,
restore, retention, manual deletion and startup repair continue through
`ArtifactStorageService`, routed by the persisted provider.

The connection test creates a unique probe blob, reads its properties, streams
and byte-compares its content, and deletes it. It never creates a container.
Provider cannot change after profile creation. Endpoint, account, container and
prefix become immutable once an execution references the profile; rename and
credential rotation remain allowed. V17 adds nullable Azure columns and leaves
all existing S3 and GCS profiles unchanged.

## Consequences

- The container must already exist; the application never creates or configures it.
- Microsoft Entra authentication is preferred. Its principal needs blob
  create, read and delete permissions, typically Storage Blob Data Contributor.
- Stored account keys grant broad authority; operators own rotation and revocation.
- `STORAGE_STAGING_DIR` must hold one complete artifact for every concurrent
  remote backup or restore. No durable local copy remains after upload.
- Soft delete, versioning, lifecycle and encryption policy are operator responsibilities.
- SAS credentials, custom CAs, customer-managed keys and browser-direct
  transfer require later decisions.
