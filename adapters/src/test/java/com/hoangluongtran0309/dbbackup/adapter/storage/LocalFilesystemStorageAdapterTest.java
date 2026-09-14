package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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

    /** The value sha256sum prints for the same bytes, so the two can be compared by eye. */
    @Test
    void checksumsAnArtifactAsSha256sumWould() throws Exception {
        Path artifact = storage.locationFor("shop.sql.gz");
        Files.writeString(artifact, "test");

        assertThat(storage.sha256Of(artifact))
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
    }

    /** Bigger than one read buffer, so the digest has to be fed in pieces. */
    @Test
    void checksumsAFileLargerThanOneBufferTheSameAsAllAtOnce() throws Exception {
        Path artifact = storage.locationFor("big.sql.gz");
        byte[] content = new byte[200_000];
        new java.util.Random(7).nextBytes(content);
        Files.write(artifact, content);

        String expected = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content));
        assertThat(storage.sha256Of(artifact)).isEqualTo(expected);
    }

    @Test
    void refusesToChecksumAFileOutsideTheStore(@TempDir Path elsewhere) throws Exception {
        Path outside = Files.writeString(elsewhere.resolve("secret"), "x");

        assertThatThrownBy(() -> storage.sha256Of(outside))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the backup directory");
    }

    @Test
    void checksummingAMissingArtifactFails() {
        assertThatThrownBy(() -> storage.sha256Of(storage.locationFor("gone.sql.gz")))
                .isInstanceOf(java.io.UncheckedIOException.class);
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
    void reportsWhetherAnArtifactIsThere() throws Exception {
        Path artifact = storage.locationFor("shop.sql.gz");
        assertThat(storage.exists(artifact)).isFalse();

        Files.writeString(artifact, "-- dump");

        assertThat(storage.exists(artifact)).isTrue();
    }

    @Test
    void doesNotMistakeADirectoryForAnArtifact() throws Exception {
        Files.createDirectory(root.resolve("notafile"));

        assertThat(storage.exists(root.resolve("notafile"))).isFalse();
    }

    @Test
    void opensAnArtifactForReading() throws Exception {
        Path artifact = Files.writeString(storage.locationFor("shop.sql.gz"), "-- dump contents");

        try (InputStream in = storage.openForReading(artifact)) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("-- dump contents");
        }
    }

    /**
     * The path handed to these methods was read back from the database. A row
     * edited by hand must not be enough to read an arbitrary file.
     */
    @Test
    void refusesToReadOutsideTheRoot(@TempDir Path elsewhere) throws Exception {
        Path outsider = Files.writeString(elsewhere.resolve("secrets.txt"), "not yours");

        assertThatThrownBy(() -> storage.openForReading(outsider))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the backup directory");
        assertThatThrownBy(() -> storage.exists(outsider))
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
