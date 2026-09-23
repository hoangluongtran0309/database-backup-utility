package com.hoangluongtran0309.dbbackup.core.model;

/** Short-lived plaintext GCS connection details. Never persist or log this value. */
public record GcsStorageConnection(
        String endpoint,
        String projectId,
        String bucket,
        String keyPrefix,
        StorageCredentialMode credentialMode,
        String serviceAccountJson) {

    @Override
    public String toString() {
        return "GcsStorageConnection[endpoint=%s, projectId=%s, bucket=%s, keyPrefix=%s, credentialMode=%s]"
                .formatted(endpoint, projectId, bucket, keyPrefix, credentialMode);
    }
}
