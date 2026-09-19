package com.hoangluongtran0309.dbbackup.adapter.sqlite;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves registered SQLite paths without letting metadata escape SQLITE_ROOT. */
@Component
class SqliteDatabaseFiles {

    private final Path root;

    SqliteDatabaseFiles(@Value("${dbbackup.sqlite.root}") Path configuredRoot) {
        Path normalized = configuredRoot.toAbsolutePath().normalize();
        try {
            Files.createDirectories(normalized);
            this.root = normalized.toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot prepare the SQLite root '%s': %s".formatted(normalized, e.getMessage()), e);
        }
        if (!Files.isDirectory(root) || !Files.isReadable(root)) {
            throw new IllegalStateException(
                    "The SQLite root '%s' must be a readable directory".formatted(root));
        }
    }

    Path resolve(String registeredPath) {
        Path relative;
        try {
            relative = Path.of(registeredPath);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("The registered SQLite database path is invalid", e);
        }
        if (relative.isAbsolute()) {
            throw outside(registeredPath);
        }
        Path candidate = root.resolve(relative).normalize();
        if (!candidate.startsWith(root)) {
            throw outside(registeredPath);
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(root)) {
                throw outside(registeredPath);
            }
            if (!Files.isRegularFile(real) || !Files.isReadable(real)) {
                throw new IllegalArgumentException(
                        "SQLite database '%s' is not a readable regular file below SQLITE_ROOT"
                                .formatted(registeredPath));
            }
            return real;
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "SQLite database '%s' does not exist or cannot be read below SQLITE_ROOT"
                            .formatted(registeredPath), e);
        }
    }

    private static IllegalArgumentException outside(String path) {
        return new IllegalArgumentException(
                "Refusing SQLite database '%s': it resolves outside SQLITE_ROOT".formatted(path));
    }
}
