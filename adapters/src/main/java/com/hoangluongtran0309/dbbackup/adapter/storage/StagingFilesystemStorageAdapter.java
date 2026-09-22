package com.hoangluongtran0309.dbbackup.adapter.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.port.StagingStoragePort;

@Component
class StagingFilesystemStorageAdapter implements StagingStoragePort {
    private final Path root;

    StagingFilesystemStorageAdapter(@Value("${dbbackup.storage.staging.root}") Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create storage staging directory " + this.root, e);
        }
    }

    @Override public Path locationFor(UUID operationId, String filename) {
        if (filename == null || filename.isBlank() || filename.contains("/") || filename.contains("\\")
                || ".".equals(filename) || "..".equals(filename)) {
            throw new IllegalArgumentException("Staging filename must be a bare filename");
        }
        Path directory = operation(operationId);
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return directory.resolve(filename);
    }

    @Override public long sizeOf(Path path) {
        try {
            return Files.size(within(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override public String sha256Of(Path path) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream input = Files.newInputStream(within(path))) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = input.read(buffer)) != -1;) {
                digest.update(buffer, 0, read);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @Override public void deleteOperation(UUID operationId) {
        Path directory = operation(operationId);
        if (!Files.exists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path operation(UUID id) {
        if (id == null) throw new IllegalArgumentException("Operation id is required");
        return root.resolve(id.toString()).normalize();
    }

    private Path within(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(root)) {
            throw new IllegalArgumentException("Refusing to access a path outside storage staging");
        }
        return normalized;
    }
}
