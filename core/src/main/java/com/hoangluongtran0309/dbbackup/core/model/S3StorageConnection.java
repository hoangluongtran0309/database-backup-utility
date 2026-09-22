package com.hoangluongtran0309.dbbackup.core.model;

/** Short-lived plaintext connection details. Never persist or log this value. */
public record S3StorageConnection(
        String endpoint,
        String region,
        String bucket,
        String keyPrefix,
        boolean pathStyle,
        StorageCredentialMode credentialMode,
        String accessKeyId,
        String secretAccessKey) {

    @Override
    public String toString() {
        return "S3StorageConnection[endpoint=%s, region=%s, bucket=%s, keyPrefix=%s, pathStyle=%s, credentialMode=%s]"
                .formatted(endpoint, region, bucket, keyPrefix, pathStyle, credentialMode);
    }
}
