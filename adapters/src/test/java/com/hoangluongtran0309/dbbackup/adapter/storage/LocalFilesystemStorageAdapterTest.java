package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

class LocalFilesystemStorageAdapterTest {

    @TempDir
    Path root;

    private StoragePort storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFilesystemStorageAdapter(root);
    }

    @Test
    void placesArtifactsUnderTheConfiguredRoot() {
        assertThat(storage.locationFor("shop_20260909_100000.sql"))
                .isEqualTo(root.resolve("shop_20260909_100000.sql"))
                .isAbsolute();
    }

    @Test
    void createsTheRootIfItDoesNotExist() {
        Path nested = root.resolve("does/not/exist/yet");

        new LocalFilesystemStorageAdapter(nested);

        assertThat(nested).isDirectory();
    }

    /**
     * The name is derived from a target's schema name, which is typed by a
     * user. A schema called {@code ../../etc} must not choose where a file
     * lands.
     */
    @ParameterizedTest
    @ValueSource(strings = {"../escape.sql", "sub/dir.sql", "..", "a\\b.sql", "../../etc/passwd"})
    void refusesAnythingThatIsAPathRatherThanAName(String filename) {
        assertThatThrownBy(() -> storage.locationFor(filename))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain a path");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void refusesABlankName(String filename) {
        assertThatThrownBy(() -> storage.locationFor(filename))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletesAnArtifact() throws Exception {
        Path artifact = Files.writeString(storage.locationFor("shop.sql"), "-- dump");

        storage.delete(artifact);

        assertThat(artifact).doesNotExist();
    }

    @Test
    void deletingSomethingAlreadyGoneIsNotAnError() {
        storage.delete(storage.locationFor("never-existed.sql"));
    }

    /**
     * The path handed to delete() was read back from the database, and a value
     * in a database is not a reason to trust it.
     */
    @Test
    void refusesToDeleteOutsideTheRoot(@TempDir Path elsewhere) throws Exception {
        Path outsider = Files.writeString(elsewhere.resolve("precious.txt"), "keep me");

        assertThatThrownBy(() -> storage.delete(outsider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the backup directory");
        assertThat(outsider).exists();
    }

    @Test
    void refusesToEscapeTheRootViaDotDot() throws Exception {
        Path outsider = Files.writeString(root.getParent().resolve("precious.txt"), "keep me");

        assertThatThrownBy(() -> storage.delete(root.resolve("../precious.txt")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(outsider).exists();
        Files.delete(outsider);
    }
}
