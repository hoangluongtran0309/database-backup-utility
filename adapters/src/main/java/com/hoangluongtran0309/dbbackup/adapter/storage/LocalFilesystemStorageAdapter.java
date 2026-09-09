package com.hoangluongtran0309.dbbackup.adapter.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

/**
 * Keeps backup artifacts in a directory on the local filesystem.
 */
@Component
class LocalFilesystemStorageAdapter implements StoragePort {

    private final Path root;

    LocalFilesystemStorageAdapter(@Value("${dbbackup.storage.local.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            // At startup, so an unwritable backup directory is discovered now
            // rather than by the first backup that tries to use it.
            throw new IllegalStateException(
                    "Cannot create the backup directory '%s': %s".formatted(this.root, e.getMessage()), e);
        }
        if (!Files.isWritable(this.root)) {
            throw new IllegalStateException(
                    "The backup directory '%s' is not writable".formatted(this.root));
        }
    }

    @Override
    public Path locationFor(String filename) {
        return root.resolve(requireBareFilename(filename));
    }

    @Override
    public void delete(Path artifact) {
        Path normalised = artifact.toAbsolutePath().normalize();
        // Refuse to delete outside the store even if asked to. The path
        // reaching here came from the database, and a value in a database is
        // not a reason to trust it.
        if (!normalised.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Refusing to delete '%s': it is outside the backup directory".formatted(artifact));
        }
        try {
            Files.deleteIfExists(normalised);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A name, not a path. Everything that reaches this comes from a target's
     * database name, and a schema called {@code ../../etc} must not be able to
     * choose where a file lands.
     */
    private static String requireBareFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("Artifact filename is required");
        }
        if (filename.contains("/") || filename.contains("\\") || filename.contains("..")) {
            throw new IllegalArgumentException(
                    "Artifact filename must not contain a path: '%s'".formatted(filename));
        }
        return filename;
    }
}
