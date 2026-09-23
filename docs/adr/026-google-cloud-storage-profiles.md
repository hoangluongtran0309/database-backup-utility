# ADR-026: Google Cloud Storage uses its native API and resumable uploads

## Status

Accepted.

**Extends** ADR-025's provider-neutral artifact reference and local staging
workflow. Existing local and S3 artifacts are unchanged.

## Context

Google Cloud Storage can expose S3 interoperability credentials, but treating
it as S3 would hide its normal authentication model and native upload
semantics. Operators need GCS as a first-class destination while every engine
continues to read and write a local `Path`. Historical artifacts must remain
bound to the provider and profile that received them.

Long-lived service-account keys are operationally expensive and should not be
the default. Workloads on Google Cloud can instead obtain short-lived
credentials through Application Default Credentials (ADC). Some regional
deployments and local emulators also need an endpoint override.

## Decision

`StorageProfile` has an immutable provider discriminator. Shared fields are the
name, existing bucket, object prefix, optional endpoint and connection check.
GCS adds a required project ID and either ADC or an AES-256-GCM-encrypted
service-account JSON key of at most 64 KiB. JSON-key mode accepts only
`ServiceAccountCredentials`, not arbitrary Google credential configuration.
The plaintext exists only in a short-lived `GcsStorageConnection`, whose
string representation never includes it, and never returns to the browser.

GCS uses `google-cloud-storage` and the native JSON API. A staged backup is
uploaded with `Storage#createFrom(BlobInfo, Path)`, which uses a resumable
upload. Downloads and checksum verification read the remote object as a
stream; restore materializes it into an operation-private staging directory.
Object keys keep ADR-025's
`<prefix>/<target-id>/<execution-id>/<sanitized-filename>` layout. Upload,
restore, retention, manual deletion and startup repair all continue through
`ArtifactStorageService`, routed by the persisted provider.

The connection test creates a unique probe object, reads its metadata, streams
and byte-compares its content, and deletes it. It never creates a bucket.
Provider cannot change after profile creation. Endpoint, project, bucket and
prefix become immutable once an execution references the profile; rename and
credential rotation remain allowed. V16 backfills every existing profile as
S3 without changing target references, credentials or artifact locators.

An empty endpoint uses Google Cloud. A custom HTTPS endpoint supports regional
deployment; HTTP is exposed only for development/test emulators. Custom CA and
insecure TLS modes are not provided.

## Consequences

- The bucket must already exist. The application never creates or configures
  it.
- ADC is preferred. Stored service-account keys require operator-managed key
  creation, least privilege, rotation and revocation.
- The identity needs `storage.objects.create`, `storage.objects.get` and
  `storage.objects.delete` on the selected bucket/prefix.
- An unfinished resumable upload session can remain valid in GCS for up to one
  week after a process crash. Operators own lifecycle monitoring and cleanup.
- `STORAGE_STAGING_DIR` must hold one complete artifact for each concurrent
  remote backup or restore. No durable local copy remains after upload.
- Bucket soft-delete, Object Versioning, lifecycle and encryption policy are
  operator responsibilities. Azure, signed URLs, browser-direct transfer,
  CMEK/CSEK and object-generation management require later decisions.
