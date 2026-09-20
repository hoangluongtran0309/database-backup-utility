package com.hoangluongtran0309.dbbackup.adapter.postgresql;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Applies a PostgreSQL custom-format archive with pg_restore. */
@Component
class PostgresRestoreAdapter implements LogicalRestorePort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final Duration timeout;

    PostgresRestoreAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.postgresql.restore-path}") Path binary,
            @Value("${dbbackup.postgresql.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
        this.processRunner = processRunner;
        this.binary = PostgresBinaries.require(binary, "dbbackup.postgresql.restore-path");
        this.connectTimeout = connectTimeout;
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    @Override
    public void restore(DatabaseConnection connection, String sourceNamespace, Path artifact) {
        if (!Files.isReadable(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is missing or unreadable".formatted(artifact));
        }
        run(listCommand(artifact), connection, "The PostgreSQL archive is not readable");
        run(restoreCommand(connection, artifact), connection, "pg_restore failed");
    }

    private void run(List<String> command, DatabaseConnection connection, String prefix) {
        ProcessRunner.Result result;
        try {
            result = processRunner.run(
                    command,
                    PostgresCliConnectionTestAdapter.environment(connection, connectTimeout),
                    timeout);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new RestoreFailedException(
                    "%s (exit %d): %s".formatted(prefix, result.exitCode(), result.errorOutput()));
        }
    }

    List<String> listCommand(Path artifact) {
        return List.of(binary.toString(), "--list", artifact.toString());
    }

    List<String> restoreCommand(DatabaseConnection connection, Path artifact) {
        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "--host=" + connection.host(),
                "--port=" + connection.port(),
                "--username=" + connection.username(),
                "--dbname=" + connection.database(),
                "--no-password",
                "--clean",
                "--if-exists",
                "--no-owner",
                "--no-privileges",
                "--exit-on-error"));
        command.add(artifact.toString());
        return List.copyOf(command);
    }
}
