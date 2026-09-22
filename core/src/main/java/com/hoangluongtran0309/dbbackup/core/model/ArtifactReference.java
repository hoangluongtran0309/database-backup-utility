package com.hoangluongtran0309.dbbackup.core.model;

import java.util.UUID;

/** A provider-neutral reference to one durable backup artifact. */
public record ArtifactReference(UUID storageProfileId, String locator) {

    public ArtifactReference {
        if (locator == null || locator.isBlank()) {
            throw new IllegalArgumentException("Artifact locator is required");
        }
        locator = locator.strip();
    }

    public boolean isLocal() {
        return storageProfileId == null;
    }
}
