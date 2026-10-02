package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

class LocalFilesystemStorageAdapterTest {

    @TempDir
    Path root;

    private static final UUID TARGET = UUID.fromString("0c95c68c-e1aa-4eb9-b63a-ba3980d10c05");
    private static final UUID EXECUTION = UUID.fromString("365da2d3-1fbe-4723-83dd-7f514a651d87");

    private StoragePort storage;

    private Path location(String filename) {
        return storage.locationFor(TARGET, EXECUTION, filename);
    }

    @BeforeEach
    void setUp() {
        storage = new LocalFilesystemStorageAdapter(root);
    }

    @Test
    void placesEachArtifactInADirectoryForItsTargetAndExecution() {
        Path location = location("shop_20260909_100000.sql");

        assertThat(location)
                .isEqualTo(root.resolve(TARGET.toString()).resolve(EXECUTION.toString())
                        .resolve("shop_20260909_100000.sql"))
                .isAbsolute();
        assertThat(location.getParent()).isDirectory();
    }

    /**
     * Two backups named after the same database in the same second used to
     * write one file, and the second truncated the first. See ISSUE-01.
     */
    @Test
    void givesTwoExecutionsWithTheSameFileNameDifferentFiles() throws Exception {
        String name = "shop_20261001_032242.sql.gz";
        Path first = storage.locationFor(TARGET, EXECUTION, name);
        Path second = storage.locationFor(UUID.randomUUID(), UUID.randomUUID(), name);
        Path sameTarget = storage.locationFor(TARGET, UUID.randomUUID(), name);
        Files.writeString(first, "mariadb");
        Files.writeString(second, "mysql");
        Files.writeString(sameTarget, "again");

        assertThat(first).hasContent("mariadb");
        assertThat(second).hasContent("mysql");
        assertThat(sameTarget).hasContent("again");
    }

    @Test
    void deletingAnArtifactRemovesTheDirectoriesItLeavesEmpty() throws Exception {
        Path artifact = Files.writeString(location("shop.sql"), "-- dump");

        storage.delete(artifact);

        assertThat(root.resolve(TARGET.toString())).doesNotExist();
        assertThat(root).isDirectory();
    }

    @Test
    void deletingOneArtifactKeepsItsTargetsOtherBackups() throws Exception {
        Path deleted = Files.writeString(location("shop.sql"), "-- dump");
        Path kept = Files.writeString(storage.locationFor(TARGET, UUID.randomUUID(), "shop.sql"), "-- other");

        storage.delete(deleted);

        assertThat(deleted.getParent()).doesNotExist();
        assertThat(kept).hasContent("-- other");
    }

    /** Artifacts written before ADR-033 sit directly in the root. */
    @Test
    void stillReadsAndDeletesAFlatArtifactFromBeforeTheLayoutChange() throws Exception {
        Path flat = Files.writeString(root.resolve("shop_20260909_100000.sql.gz"), "test");

        assertThat(storage.exists(flat)).isTrue();
        assertThat(storage.sha256Of(flat))
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");

        storage.delete(flat);

        assertThat(flat).doesNotExist();
        assertThat(root).isDirectory();
    }

    /** The value sha256sum prints for the same bytes, so the two can be compared by eye. */
    @Test
    void checksumsAnArtifactAsSha256sumWould() throws Exception {
        Path artifact = location("shop.sql.gz");
        Files.writeString(artifact, "test");

        assertThat(storage.sha256Of(artifact))
                .isEqualTo("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08");
    }

    /** Bigger than one read buffer, so the digest has to be fed in pieces. */
    @Test
    void checksumsAFileLargerThanOneBufferTheSameAsAllAtOnce() throws Exception {
        Path artifact = location("big.sql.gz");
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
        assertThatThrownBy(() -> storage.sha256Of(location("gone.sql.gz")))
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
        assertThatThrownBy(() -> location(filename))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not contain a path");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void refusesABlankName(String filename) {
        assertThatThrownBy(() -> location(filename))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsWhetherAnArtifactIsThere() throws Exception {
        Path artifact = location("shop.sql.gz");
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
        Path artifact = Files.writeString(location("shop.sql.gz"), "-- dump contents");

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
        Path artifact = Files.writeString(location("shop.sql"), "-- dump");

        storage.delete(artifact);

        assertThat(artifact).doesNotExist();
    }

    @Test
    void deletingSomethingAlreadyGoneIsNotAnError() {
        storage.delete(location("never-existed.sql"));
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
