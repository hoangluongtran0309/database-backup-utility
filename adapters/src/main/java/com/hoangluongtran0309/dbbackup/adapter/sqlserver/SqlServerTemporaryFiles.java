package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Per-operation scratch space for SqlPackage's uncompressed table data. */
@Component
@ConditionalOnProperty(name = "dbbackup.sqlserver.enabled", havingValue = "true")
class SqlServerTemporaryFiles {

    private final Path root;

    SqlServerTemporaryFiles(@Value("${dbbackup.sqlserver.temp-directory}") Path configuredRoot) {
        try {
            Files.createDirectories(configuredRoot);
            this.root = configuredRoot.toRealPath();
            if (!Files.isReadable(root) || !Files.isWritable(root) || !Files.isDirectory(root)) {
                throw new IllegalStateException(
                        "dbbackup.sqlserver.temp-directory must be a readable and writable directory: " + root);
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "dbbackup.sqlserver.temp-directory could not be prepared: " + configuredRoot, e);
        }
    }

    @FunctionalInterface
    interface Operation<T> {
        T run(Path directory);
    }

    <T> T use(Operation<T> operation) {
        Path directory;
        try {
            directory = Files.createTempDirectory(root, "operation-");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create SQL Server temporary directory", e);
        }

        Throwable failure = null;
        try {
            return operation.run(directory);
        } catch (RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            try {
                deleteTree(directory);
            } catch (IOException e) {
                UncheckedIOException cleanup = new UncheckedIOException(
                        "Could not remove SQL Server temporary directory '" + directory + "'", e);
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static void deleteTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
