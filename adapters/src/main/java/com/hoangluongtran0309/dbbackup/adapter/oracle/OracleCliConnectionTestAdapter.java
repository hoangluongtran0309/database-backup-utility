package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

/** Probes login, directory grants and the shared mount without starting a Data Pump job. */
@Component
@ConditionalOnProperty(name = "dbbackup.oracle.enabled", havingValue = "true")
class OracleCliConnectionTestAdapter implements ConnectionTestPort {

    private final ProcessRunner runner;
    private final Path binary;
    private final OracleDataPumpFiles files;
    private final Duration timeout;

    OracleCliConnectionTestAdapter(
            ProcessRunner runner,
            OracleDataPumpFiles files,
            @Value("${dbbackup.oracle.sqlplus-path}") Path binary,
            @Value("${dbbackup.oracle.connect-timeout}") Duration timeout) {
        this.runner = runner;
        this.files = files;
        this.binary = OracleBinaries.require(binary, "dbbackup.oracle.sqlplus-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ORACLE;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        Path probe = files.probe(UUID.randomUUID());
        String filename = probe.getFileName().toString();
        String writeThroughOracle = """
                WHENEVER SQLERROR EXIT FAILURE ROLLBACK
                DECLARE
                  f UTL_FILE.FILE_TYPE;
                BEGIN
                  f := UTL_FILE.FOPEN('%s', '%s', 'w');
                  UTL_FILE.PUT_LINE(f, 'dbbackup probe');
                  UTL_FILE.FCLOSE(f);
                END;
                /
                EXIT SUCCESS
                """.formatted(connection.dataPumpDirectory(), filename);
        try {
            ProcessRunner.Result result = OracleClient.runSqlPlus(
                    runner, binary, connection, timeout, writeThroughOracle);
            if (!result.succeeded()) {
                return failedAfterCleanup(connection, probe, filename, result.errorOutput());
            }
            files.requireReadableRegularFile(probe,
                    "Oracle created a staging probe that the application cannot read");
            removeProbe(connection, probe, filename);

            files.writeProbe(probe);
            ProcessRunner.Result reverse = OracleClient.runSqlPlus(
                    runner, binary, connection, timeout, readThroughOracle(connection, filename));
            if (!reverse.succeeded()) {
                return failedAfterCleanup(connection, probe, filename,
                        files.oracleReadFailure(probe, reverse.errorOutput()));
            }
            return finishAfterCleanup(connection, probe, filename);
        } catch (RuntimeException e) {
            return failedAfterCleanup(connection, probe, filename, e.getMessage());
        }
    }

    private static String readThroughOracle(DatabaseConnection connection, String filename) {
        return """
                WHENEVER SQLERROR EXIT FAILURE ROLLBACK
                DECLARE
                  f UTL_FILE.FILE_TYPE;
                  line VARCHAR2(32767);
                BEGIN
                  f := UTL_FILE.FOPEN('%s', '%s', 'r');
                  UTL_FILE.GET_LINE(f, line);
                  UTL_FILE.FCLOSE(f);
                END;
                /
                EXIT SUCCESS
                """.formatted(connection.dataPumpDirectory(), filename);
    }

    private Result finishAfterCleanup(DatabaseConnection connection, Path probe, String filename) {
        try {
            removeProbe(connection, probe, filename);
            return Result.ok();
        } catch (RuntimeException cleanup) {
            return Result.failed("Oracle staging probe cleanup failed: " + cleanup.getMessage());
        }
    }

    private Result failedAfterCleanup(
            DatabaseConnection connection, Path probe, String filename, String message) {
        try {
            removeProbe(connection, probe, filename);
            return Result.failed(message);
        } catch (RuntimeException cleanup) {
            return Result.failed(message + " (probe cleanup also failed: " + cleanup.getMessage() + ")");
        }
    }

    /** Uses Oracle for cleanup when a broken mount makes the probe invisible locally. */
    private void removeProbe(DatabaseConnection connection, Path probe, String filename) {
        if (files.isReadableRegularFile(probe)) {
            files.delete(probe);
            return;
        }
        String cleanup = """
                BEGIN
                  UTL_FILE.FREMOVE('%s', '%s');
                EXCEPTION
                  WHEN OTHERS THEN NULL;
                END;
                /
                EXIT SUCCESS
                """.formatted(connection.dataPumpDirectory(), filename);
        OracleClient.runSqlPlus(runner, binary, connection, timeout, cleanup);
    }
}
