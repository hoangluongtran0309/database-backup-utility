package com.hoangluongtran0309.dbbackup.adapter.postgresql;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

/** Tests PostgreSQL through psql, the same client family the backup path uses. */
@Component
class PostgresCliConnectionTestAdapter implements ConnectionTestPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;

    PostgresCliConnectionTestAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.postgresql.psql-path}") Path binary,
            @Value("${dbbackup.postgresql.connect-timeout}") Duration connectTimeout) {
        this.processRunner = processRunner;
        this.binary = PostgresBinaries.require(binary, "dbbackup.postgresql.psql-path");
        this.connectTimeout = connectTimeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.POSTGRESQL;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        try {
            ProcessRunner.Result result = processRunner.run(
                    List.of(
                            binary.toString(),
                            "--host=" + connection.host(),
                            "--port=" + connection.port(),
                            "--username=" + connection.username(),
                            "--dbname=" + connection.database(),
                            "--no-password",
                            "--tuples-only",
                            "--no-align",
                            "--command=SELECT 1"),
                    environment(connection, connectTimeout),
                    connectTimeout.plusSeconds(5));
            return result.succeeded() ? Result.ok() : Result.failed(result.errorOutput());
        } catch (ProcessRunner.ProcessFailedException e) {
            return Result.failed(e.getMessage());
        }
    }

    static Map<String, String> environment(DatabaseConnection connection, Duration connectTimeout) {
        return Map.of(
                "PGPASSWORD", connection.password(),
                "PGCONNECT_TIMEOUT", Long.toString(connectTimeout.toSeconds()));
    }
}
