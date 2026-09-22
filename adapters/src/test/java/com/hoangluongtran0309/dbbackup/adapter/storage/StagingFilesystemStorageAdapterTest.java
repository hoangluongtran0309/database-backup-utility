package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StagingFilesystemStorageAdapterTest {
    @TempDir Path root;

    @Test
    void createsOnePrivateDirectoryPerOperationAndCleansIt() throws Exception {
        StagingFilesystemStorageAdapter staging = new StagingFilesystemStorageAdapter(root);
        UUID executionId = UUID.randomUUID();
        Path file = staging.locationFor(executionId, "shop.sql.gz");
        Files.writeString(file, "artifact");

        assertThat(file).isEqualTo(root.resolve(executionId.toString()).resolve("shop.sql.gz"));
        staging.deleteOperation(executionId);
        assertThat(file.getParent()).doesNotExist();
    }

    @Test
    void acceptsSanitizedNamesContainingDotsButRejectsPathSeparators() {
        StagingFilesystemStorageAdapter staging = new StagingFilesystemStorageAdapter(root);
        assertThat(staging.locationFor(UUID.randomUUID(), ".._.._etc_20260922.sql.gz")
                .normalize().startsWith(root.toAbsolutePath().normalize())).isTrue();
        assertThatThrownBy(() -> staging.locationFor(UUID.randomUUID(), "../escape.sql.gz"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void hashesOnlyFilesInsideStaging() throws Exception {
        StagingFilesystemStorageAdapter staging = new StagingFilesystemStorageAdapter(root);
        Path file = staging.locationFor(UUID.randomUUID(), "artifact.bin");
        Files.writeString(file, "test");
        assertThat(staging.sizeOf(file)).isEqualTo(4);
        assertThat(staging.sha256Of(file))
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
        assertThatThrownBy(() -> staging.sizeOf(root.resolveSibling("outside")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
