package com.hoangluongtran0309.dbbackup.adapter.postgresql;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/** Produces a compressed PostgreSQL custom-format archive with pg_dump. */
@Component
class PostgresDumpBackupAdapter implements LogicalBackupPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final Duration timeout;

    PostgresDumpBackupAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.postgresql.dump-path}") Path binary,
            @Value("${dbbackup.postgresql.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.backup.timeout}") Duration timeout) {
        this.processRunner = processRunner;
        this.binary = PostgresBinaries.require(binary, "dbbackup.postgresql.dump-path");
        this.connectTimeout = connectTimeout;
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    @Override
    public String artifactSuffix() {
        return ".dump";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        try {
            ProcessRunner.Result result = processRunner.run(
                    command(connection, destination),
                    PostgresCliConnectionTestAdapter.environment(connection, connectTimeout),
                    timeout);
            if (!result.succeeded()) {
                throw failed(destination,
                        "pg_dump exited with %d: %s".formatted(result.exitCode(), result.errorOutput()));
            }
            return Files.size(destination);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw failed(destination, e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> command(DatabaseConnection connection, Path destination) {
        return List.of(
                binary.toString(),
                "--host=" + connection.host(),
                "--port=" + connection.port(),
                "--username=" + connection.username(),
                "--dbname=" + connection.database(),
                "--no-password",
                "--format=custom",
                "--no-owner",
                "--no-privileges",
                "--file=" + destination);
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
}
