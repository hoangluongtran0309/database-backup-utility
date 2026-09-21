package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Validates and streams a gzipped SQL artifact into MariaDB's own client. */
@Component
class MariaDbRestoreAdapter implements LogicalRestorePort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final Duration timeout;

    MariaDbRestoreAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mariadb.client-path}") Path binary,
            @Value("${dbbackup.mariadb.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {

        this.processRunner = processRunner;
        this.binary = MariaDbBinaries.require(binary, "dbbackup.mariadb.client-path");
        this.connectTimeout = connectTimeout;
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MARIADB;
    }

    @Override
    public void restore(DatabaseConnection connection, String sourceNamespace, Path artifact) {
        if (!Files.isReadable(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is missing or unreadable".formatted(artifact));
        }
        checkArchive(artifact);

        ProcessRunner.Result result;
        InputStream sql = open(artifact);
        try {
            result = processRunner.runFeeding(
                    command(connection), MariaDbClient.environment(connection), timeout, sql);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        } finally {
            closeQuietly(sql);
        }

        if (!result.succeeded()) {
            throw new RestoreFailedException(
                    "mariadb exited with %d: %s".formatted(result.exitCode(), result.errorOutput()));
        }
    }

    List<String> command(DatabaseConnection connection) {
        return List.of(
                binary.toString(),
                "--protocol=TCP",
                "--host=" + connection.host(),
                "--port=" + connection.port(),
                "--user=" + connection.username(),
                "--connect-timeout=" + connectTimeout.toSeconds(),
                "--batch",
                connection.database());
    }

    private static void checkArchive(Path artifact) {
        try (InputStream in = open(artifact)) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException e) {
            throw unreadable(artifact, e);
        }
    }

    private static InputStream open(Path artifact) {
        try {
            return new GZIPInputStream(Files.newInputStream(artifact));
        } catch (IOException e) {
            throw unreadable(artifact, e);
        }
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(MariaDbRestoreAdapter.class)
                    .warn("Could not close the backup artifact after restoring from it", e);
        }
    }

    private static RestoreFailedException unreadable(Path artifact, IOException e) {
        return new RestoreFailedException(
                "Could not read the backup artifact '%s': %s. Nothing was restored."
                        .formatted(artifact, e.getMessage()), e);
    }
}
