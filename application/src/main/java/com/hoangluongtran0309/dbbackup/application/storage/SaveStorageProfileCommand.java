package com.hoangluongtran0309.dbbackup.application.storage;

import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;

public record SaveStorageProfileCommand(
        String name,
        String endpoint,
        String region,
        String bucket,
        String keyPrefix,
        boolean pathStyle,
        StorageCredentialMode credentialMode,
        String accessKeyId,
        String secretAccessKey) {
    public boolean suppliesSecret() {
        return secretAccessKey != null && !secretAccessKey.isBlank();
    }
}
