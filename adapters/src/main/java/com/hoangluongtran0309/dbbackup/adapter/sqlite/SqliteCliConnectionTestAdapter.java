package com.hoangluongtran0309.dbbackup.adapter.sqlite;

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

/** Opens the registered file read-only and asks SQLite for a lightweight consistency check. */
@Component
class SqliteCliConnectionTestAdapter implements ConnectionTestPort {

    private final ProcessRunner runner;
    private final SqliteDatabaseFiles files;
    private final Path binary;
    private final Duration timeout;

    SqliteCliConnectionTestAdapter(
            ProcessRunner runner,
            SqliteDatabaseFiles files,
            @Value("${dbbackup.sqlite.client-path}") Path binary,
            @Value("${dbbackup.sqlite.connect-timeout}") Duration timeout) {
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
    public Result test(DatabaseConnection connection) {
        try {
            Path database = files.resolve(connection.database());
            ProcessRunner.Result result = runner.run(checkCommand(database, "PRAGMA quick_check;"), Map.of(), timeout);
            if (result.succeeded() && result.stderr().isBlank() && result.stdout().strip().equals("ok")) {
                return Result.ok();
            }
            return Result.failed(problem(result, "SQLite quick_check did not return ok"));
        } catch (ProcessRunner.ProcessFailedException | IllegalArgumentException e) {
            return Result.failed(e.getMessage());
        }
    }

    List<String> checkCommand(Path database, String pragma) {
        return List.of(binary.toString(), "-readonly", database.toString(), pragma);
    }

    static String problem(ProcessRunner.Result result, String fallback) {
        String output = result.errorOutput();
        if (output.isBlank() && !result.stdout().isBlank()) {
            output = result.stdout().strip();
        }
        return output.isBlank() ? fallback : output;
    }
}
