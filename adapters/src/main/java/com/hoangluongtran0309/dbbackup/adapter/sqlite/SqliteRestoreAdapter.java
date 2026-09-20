package com.hoangluongtran0309.dbbackup.adapter.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Rebuilds and validates a temporary database before replacing the destination through SQLite. */
@Component
class SqliteRestoreAdapter implements LogicalRestorePort {

    private static final Logger log = LoggerFactory.getLogger(SqliteRestoreAdapter.class);

    private final ProcessRunner runner;
    private final SqliteDatabaseFiles files;
    private final Path binary;
    private final Duration timeout;

    SqliteRestoreAdapter(
            ProcessRunner runner,
            SqliteDatabaseFiles files,
            @Value("${dbbackup.sqlite.client-path}") Path binary,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
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
    public void restore(DatabaseConnection connection, String sourceNamespace, Path artifact) {
        if (!Files.isReadable(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is missing or unreadable".formatted(artifact));
        }
        Path temporaryDirectory = null;
        try {
            temporaryDirectory = temporaryDirectory();
            Path rebuilt = temporaryDirectory.resolve("restored.db");
            rebuild(artifact, rebuilt);
            requireIntegrity(rebuilt);
            Path destination = files.resolve(connection.database());
            ProcessRunner.Result result = runner.run(restoreCommand(destination, rebuilt), Map.of(), timeout);
            requireSuccess(result, "sqlite3 .restore failed");
        } catch (RestoreFailedException e) {
            throw e;
        } catch (ProcessRunner.ProcessFailedException | IllegalArgumentException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        } catch (IOException e) {
            throw new RestoreFailedException("Could not prepare the SQLite restore: " + e.getMessage(), e);
        } finally {
            deleteTemporaryDirectory(temporaryDirectory);
        }
    }

    private void rebuild(Path artifact, Path database) throws IOException {
        ProcessRunner.Result result;
        try (InputStream sql = new GZIPInputStream(Files.newInputStream(artifact))) {
            result = runner.runFeeding(
                    List.of(binary.toString(), "-bail", database.toString()), Map.of(), timeout, sql);
        }
        requireSuccess(result, "The SQLite backup SQL could not be loaded");
    }

    private void requireIntegrity(Path database) {
        ProcessRunner.Result result = runner.run(
                List.of(binary.toString(), "-readonly", database.toString(), "PRAGMA integrity_check;"),
                Map.of(), timeout);
        if (!result.succeeded() || !result.stderr().isBlank() || !result.stdout().strip().equals("ok")) {
            throw new RestoreFailedException("The rebuilt SQLite database failed integrity_check: "
                    + SqliteCliConnectionTestAdapter.problem(result, "result was not ok"));
        }
    }

    private static void requireSuccess(ProcessRunner.Result result, String prefix) {
        if (!result.succeeded() || !result.stderr().isBlank()) {
            throw new RestoreFailedException(prefix + ": "
                    + SqliteCliConnectionTestAdapter.problem(result, "unknown SQLite error"));
        }
    }

    List<String> restoreCommand(Path destination, Path rebuilt) {
        return List.of(binary.toString(), destination.toString(), ".restore " + dotQuoted(rebuilt));
    }

    private static String dotQuoted(Path path) {
        return "\"" + path.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Path temporaryDirectory() throws IOException {
        try {
            return Files.createTempDirectory(
                    "dbbackup-sqlite-restore-",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            return Files.createTempDirectory("dbbackup-sqlite-restore-");
        }
    }

    private static void deleteTemporaryDirectory(Path directory) {
        if (directory == null) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            log.warn("Could not remove temporary SQLite restore directory {}", directory, e);
        }
    }
}
