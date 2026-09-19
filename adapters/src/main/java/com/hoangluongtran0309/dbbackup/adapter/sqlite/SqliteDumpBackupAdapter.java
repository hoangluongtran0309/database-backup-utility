package com.hoangluongtran0309.dbbackup.adapter.sqlite;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/** Streams a complete SQLite SQL dump through gzip after checking the source database. */
@Component
class SqliteDumpBackupAdapter implements LogicalBackupPort {

    private static final int GZIP_BUFFER_BYTES = 64 * 1024;

    private final ProcessRunner runner;
    private final SqliteDatabaseFiles files;
    private final Path binary;
    private final Duration timeout;

    SqliteDumpBackupAdapter(
            ProcessRunner runner,
            SqliteDatabaseFiles files,
            @Value("${dbbackup.sqlite.client-path}") Path binary,
            @Value("${dbbackup.backup.timeout}") Duration timeout) {
        this.runner = runner;
        this.files = files;
        this.binary = SqliteBinaries.require(binary, "dbbackup.sqlite.client-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.SQLITE;
    }

    @Override
    public String artifactSuffix() {
        return ".sql.gz";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        try {
            Path database = files.resolve(connection.database());
            requireIntegrity(database);
            ProcessRunner.Result result;
            try (OutputStream output = new GZIPOutputStream(
                    new BufferedOutputStream(Files.newOutputStream(destination)), GZIP_BUFFER_BYTES)) {
                result = runner.runStreaming(dumpCommand(database), Map.of(), timeout, output);
            }
            if (!result.succeeded() || !result.stderr().isBlank()) {
                throw failed(destination, "sqlite3 .dump failed: "
                        + SqliteCliConnectionTestAdapter.problem(result, "unknown SQLite error"));
            }
            return Files.size(destination);
        } catch (BackupFailedException e) {
            throw e;
        } catch (ProcessRunner.ProcessFailedException | IllegalArgumentException e) {
            throw failed(destination, e.getMessage());
        } catch (IOException e) {
            throw failed(destination, "Could not write '%s': %s".formatted(destination, e.getMessage()));
        }
    }

    private void requireIntegrity(Path database) {
        ProcessRunner.Result result = runner.run(
                List.of(binary.toString(), "-readonly", database.toString(), "PRAGMA integrity_check;"),
                Map.of(), timeout);
        if (!result.succeeded() || !result.stderr().isBlank() || !result.stdout().strip().equals("ok")) {
            throw new BackupFailedException("SQLite integrity_check failed: "
                    + SqliteCliConnectionTestAdapter.problem(result, "result was not ok"));
        }
    }

    List<String> dumpCommand(Path database) {
        return List.of(binary.toString(), "-readonly", database.toString(), ".dump");
    }

    private static BackupFailedException failed(Path destination, String message) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException cleanup) {
            return new BackupFailedException(
                    "%s (and the partial file '%s' could not be removed: %s)"
                            .formatted(message, destination, cleanup.getMessage()));
        }
        return new BackupFailedException(message);
    }
}
