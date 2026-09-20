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
        String script = """
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
                    runner, binary, connection, timeout, script);
            if (!result.succeeded()) {
                removeProbe(connection, probe, filename);
                return Result.failed(result.errorOutput());
            }
            if (!files.isReadableRegularFile(probe)) {
                String visibility = "exists=%s, regular=%s, readable=%s".formatted(
                        Files.exists(probe), Files.isRegularFile(probe), Files.isReadable(probe));
                removeProbe(connection, probe, filename);
                return Result.failed(
                        "Oracle created the probe in directory object %s, but it is not visible at %s (%s)"
                                .formatted(connection.dataPumpDirectory(), probe, visibility));
            }
            files.delete(probe);
            return Result.ok();
        } catch (RuntimeException e) {
            try {
                removeProbe(connection, probe, filename);
            } catch (RuntimeException cleanup) {
                e.addSuppressed(cleanup);
            }
            return Result.failed(e.getMessage());
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
