package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/** Streams one full MariaDB logical dump through gzip into artifact storage. */
@Component
class MariaDbDumpBackupAdapter implements LogicalBackupPort {

    private static final int GZIP_BUFFER_BYTES = 64 * 1024;

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration timeout;

    MariaDbDumpBackupAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mariadb.dump-path}") Path binary,
            @Value("${dbbackup.backup.timeout}") Duration timeout) {

        this.processRunner = processRunner;
        this.binary = MariaDbBinaries.require(binary, "dbbackup.mariadb.dump-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MARIADB;
    }

    @Override
    public String artifactSuffix() {
        return ".sql.gz";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        ProcessRunner.Result result;
        try (OutputStream out = new GZIPOutputStream(
                new BufferedOutputStream(Files.newOutputStream(destination)), GZIP_BUFFER_BYTES)) {

            result = processRunner.runStreaming(
                    command(connection), MariaDbClient.environment(connection), timeout, out);
        } catch (IOException e) {
            throw failed(destination, "Could not write '%s': %s".formatted(destination, e.getMessage()));
        } catch (ProcessRunner.ProcessFailedException e) {
            throw failed(destination, e.getMessage());
        }

        if (!result.succeeded()) {
            throw failed(destination, "mariadb-dump exited with %d: %s"
                    .formatted(result.exitCode(), result.errorOutput()));
        }
        return sizeOf(destination);
    }

    List<String> command(DatabaseConnection connection) {
        return List.of(
                binary.toString(),
                "--protocol=TCP",
                "--host=" + connection.host(),
                "--port=" + connection.port(),
                "--user=" + connection.username(),
                "--single-transaction",
                "--routines",
                "--triggers",
                "--events",
                // Positional rather than --databases: do not put CREATE
                // DATABASE or USE in an artifact restored into another target.
                connection.database());
    }

    private static BackupFailedException failed(Path destination, String message) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException e) {
            return new BackupFailedException(
                    "%s (and the partial file '%s' could not be removed: %s)"
                            .formatted(message, destination, e.getMessage()));
        }
        return new BackupFailedException(message);
    }

    private static long sizeOf(Path destination) {
        try {
            return Files.size(destination);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
