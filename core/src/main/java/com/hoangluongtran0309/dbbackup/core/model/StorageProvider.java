package com.hoangluongtran0309.dbbackup.core.model;

public enum StorageProvider {
    S3("S3-compatible"),
    GCS("Google Cloud Storage");

    private final String displayName;

    StorageProvider(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
