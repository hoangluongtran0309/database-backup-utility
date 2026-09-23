package com.hoangluongtran0309.dbbackup.core.model;

/** Short-lived plaintext Azure Blob connection details. Never persist or log this value. */
public record AzureBlobStorageConnection(
        String endpoint,
        String accountName,
        String container,
        String keyPrefix,
        StorageCredentialMode credentialMode,
        String accountKey) {

    @Override
    public String toString() {
        return "AzureBlobStorageConnection[endpoint=%s, accountName=%s, container=%s, keyPrefix=%s, credentialMode=%s]"
                .formatted(endpoint, accountName, container, keyPrefix, credentialMode);
    }
}
