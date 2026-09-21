package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

import lombok.RequiredArgsConstructor;

/** Probes a MariaDB target through the same client family used for restore. */
@Component
@RequiredArgsConstructor
class MariaDbCliConnectionTestAdapter implements ConnectionTestPort {

    private final MariaDbClient client;

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MARIADB;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        try {
            ProcessRunner.Result result = client.execute(connection, "SELECT 1");
            return result.succeeded() ? Result.ok() : Result.failed(result.errorOutput());
        } catch (ProcessRunner.ProcessFailedException e) {
            return Result.failed(e.getMessage());
        }
    }
}
