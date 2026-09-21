package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

/** Tests SQL Server with a bounded SELECT through sqlcmd. */
@Component
@ConditionalOnProperty(name = "dbbackup.sqlserver.enabled", havingValue = "true")
class SqlServerCliConnectionTestAdapter implements ConnectionTestPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final boolean trustServerCertificate;

    SqlServerCliConnectionTestAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.sqlserver.sqlcmd-path}") Path binary,
            @Value("${dbbackup.sqlserver.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.sqlserver.trust-server-certificate}") boolean trustServerCertificate) {
        this.processRunner = processRunner;
        this.binary = SqlServerBinaries.require(binary, "dbbackup.sqlserver.sqlcmd-path");
        this.connectTimeout = connectTimeout;
        this.trustServerCertificate = trustServerCertificate;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.SQLSERVER;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        try {
            ProcessRunner.Result result = processRunner.run(
                    command(connection),
                    Map.of("SQLCMDPASSWORD", connection.password()),
                    connectTimeout.plusSeconds(5));
            return result.succeeded() ? Result.ok() : Result.failed(result.errorOutput());
        } catch (ProcessRunner.ProcessFailedException e) {
            return Result.failed(e.getMessage());
        }
    }

    List<String> command(DatabaseConnection connection) {
        List<String> command = new ArrayList<>(List.of(
                binary.toString(),
                "-S", server(connection),
                "-d", connection.database(),
                "-U", connection.username(),
                "-N",
                "-b",
                "-l", Long.toString(Math.max(1, connectTimeout.toSeconds())),
                "-Q", "SET NOCOUNT ON; SELECT 1"));
        if (trustServerCertificate) {
            command.add("-C");
        }
        return List.copyOf(command);
    }

    static String server(DatabaseConnection connection) {
        return "tcp:%s,%d".formatted(connection.host(), connection.port());
    }
}
