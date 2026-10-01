package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** The application-side view of the directory mounted into every Oracle server. */
@Component
@ConditionalOnProperty(name = "dbbackup.oracle.enabled", havingValue = "true")
class OracleDataPumpFiles {

    /**
     * Data Pump creates its files readable only by the Oracle server's user and
     * group, and Oracle must read the files the application stages for import.
     * See ADR-034.
     */
    static final String SHARED_GROUP_HINT = "Configure ORACLE_DATAPUMP_ROOT with a group shared by the "
            + "application and the Oracle server, add the application to that numeric group, and make the "
            + "directory setgid so application-staged files inherit it (or configure equivalent ACLs; "
            + "see docs/deployment.md).";

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

    void writeProbe(Path path) {
        requireOwnedPath(path);
        try {
            Files.writeString(path, "dbbackup probe\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create the application-side Oracle staging probe '"
                    + path + "'", e);
        }
    }

    void copyFromDataPump(Path staged, Path destination) {
        requireReadableRegularFile(staged, "Oracle Data Pump did not produce a readable dump file");
        copy(staged, destination);
    }

    void stageForImport(Path artifact, Path staged) {
        requireReadableRegularFile(artifact, "The Oracle backup artifact is missing or unreadable");
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

    void requireReadableRegularFile(Path path, String message) {
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isReadable(path)) {
            throw new IllegalStateException("%s: %s exists but the application cannot read it (%s). %s"
                    .formatted(message, path, ownership(path), SHARED_GROUP_HINT));
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException(message + ": " + path);
        }
    }

    String oracleReadFailure(Path path, String oracleError) {
        return "Oracle cannot read the file staged by the application at %s (%s). %s Oracle reported: %s"
                .formatted(path, ownership(path), SHARED_GROUP_HINT, oracleError);
    }

    /** Owner, group and mode of a file, or why they could not be read. */
    static String ownership(Path path) {
        try {
            return "owner uid %s, gid %s, mode %s".formatted(
                    Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS),
                    Files.getAttribute(path, "unix:gid", LinkOption.NOFOLLOW_LINKS),
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)));
        } catch (IOException | UnsupportedOperationException e) {
            return "ownership unavailable: " + e.getMessage();
        }
    }

    private void requireOwnedPath(Path path) {
        Path normal = path.toAbsolutePath().normalize();
        if (!normal.getParent().equals(root) || !normal.getFileName().toString().startsWith("dbbackup-")) {
            throw new IllegalArgumentException("Refusing an Oracle staging path outside the configured root: " + path);
        }
    }
}
