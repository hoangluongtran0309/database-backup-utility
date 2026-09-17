package com.hoangluongtran0309.dbbackup.adapter.mysql;

import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

import lombok.RequiredArgsConstructor;

/**
 * Tests a target by running {@code SELECT 1} through the {@code mysql} client.
 *
 * <p>Through the client rather than JDBC on purpose. A backup runs by shelling
 * out to these same binaries with these same credentials, so probing the same
 * way tests the path that will actually be used — a JDBC connection can
 * succeed while a missing or unreadable {@code mysql} binary makes every backup
 * fail. It also keeps a MySQL JDBC driver out of the application entirely.
 */
@Component
@RequiredArgsConstructor
class MysqlCliConnectionTestAdapter implements ConnectionTestPort {

    private final MysqlClient client;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MYSQL;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        try {
            ProcessRunner.Result result = client.execute(connection, "SELECT 1");
            return result.succeeded()
                    ? Result.ok()
                    // MySQL's own words, verbatim: "Access denied for user" and
                    // "Unknown database" need different fixes.
                    : Result.failed(result.errorOutput());
        } catch (ProcessRunner.ProcessFailedException e) {
            // The probe itself broke — a missing binary, or a child that hung.
            // Still an answer about this target, not a server error.
            return Result.failed(e.getMessage());
        }
    }
}
