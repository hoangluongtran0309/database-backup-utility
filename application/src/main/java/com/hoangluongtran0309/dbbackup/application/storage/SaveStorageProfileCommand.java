package com.hoangluongtran0309.dbbackup.application.storage;

import java.nio.charset.StandardCharsets;

import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.model.StorageProvider;

public record SaveStorageProfileCommand(
        String name,
        StorageProvider provider,
        String endpoint,
        String region,
        String projectId,
        String accountName,
        String bucket,
        String keyPrefix,
        boolean pathStyle,
        StorageCredentialMode credentialMode,
        String accessKeyId,
        String secretAccessKey,
        String serviceAccountJson,
        String accountKey) {
    public SaveStorageProfileCommand {
        if (serviceAccountJson != null
                && serviceAccountJson.getBytes(StandardCharsets.UTF_8).length > 64 * 1024) {
            throw new IllegalArgumentException("Service-account JSON must be at most 64 KiB");
        }
    }

    public boolean suppliesSecret() {
        return secretAccessKey != null && !secretAccessKey.isBlank();
    }

    public boolean suppliesServiceAccountJson() {
        return serviceAccountJson != null && !serviceAccountJson.isBlank();
    }

    public boolean suppliesAccountKey() {
        return accountKey != null && !accountKey.isBlank();
    }
}
