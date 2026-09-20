package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The application-side view of the directory mounted into every Oracle server. */
@Component
@ConditionalOnProperty(name = "dbbackup.oracle.enabled", havingValue = "true")
class OracleDataPumpFiles {

    private final Path root;

    OracleDataPumpFiles(@Value("${dbbackup.oracle.data-pump-root}") Path configuredRoot) {
        try {
            this.root = configuredRoot.toAbsolutePath().normalize().toRealPath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "dbbackup.oracle.data-pump-root is not an existing directory: " + configuredRoot, e);
        }
        if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
            throw new IllegalStateException(
                    "dbbackup.oracle.data-pump-root must be a readable and writable directory: " + root);
        }
    }

    Path backupDump(UUID operationId) {
        return file("dbbackup-exp-", operationId, ".dmp");
    }

    Path restoreDump(UUID operationId) {
        return file("dbbackup-imp-", operationId, ".dmp");
    }

    Path sqlFile(UUID operationId) {
        return file("dbbackup-sql-", operationId, ".sql");
    }

    Path probe(UUID operationId) {
        return file("dbbackup-probe-", operationId, ".tmp");
    }

    void copyFromDataPump(Path staged, Path destination) {
        requireRegular(staged, "Oracle Data Pump did not produce a readable dump file");
        copy(staged, destination);
    }

    void stageForImport(Path artifact, Path staged) {
        requireRegular(artifact, "The Oracle backup artifact is missing or unreadable");
        copy(artifact, staged);
        try {
            if (Files.mismatch(artifact, staged) != -1) {
                delete(staged);
                throw new IllegalStateException("The Oracle artifact changed while it was copied to Data Pump staging");
            }
        } catch (IOException e) {
            delete(staged);
            throw new UncheckedIOException(e);
        }
    }

    long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    boolean isReadableRegularFile(Path path) {
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.isReadable(path);
    }

    void delete(Path path) {
        requireOwnedPath(path);
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not remove Oracle Data Pump staging file '" + path + "'", e);
        }
    }

    private Path file(String prefix, UUID operationId, String suffix) {
        return root.resolve(prefix + operationId.toString().replace("-", "") + suffix);
    }

    private void copy(Path source, Path destination, CopyOption... extra) {
        try {
            Files.copy(source, destination, extra);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void requireRegular(Path path, String message) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new IllegalStateException(message + ": " + path);
        }
    }

    private void requireOwnedPath(Path path) {
        Path normal = path.toAbsolutePath().normalize();
        if (!normal.getParent().equals(root) || !normal.getFileName().toString().startsWith("dbbackup-")) {
            throw new IllegalArgumentException("Refusing an Oracle staging path outside the configured root: " + path);
        }
    }
}
