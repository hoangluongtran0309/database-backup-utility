package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;

@Component
@ConditionalOnProperty(name = "dbbackup.oracle.enabled", havingValue = "true")
class OracleDataPumpJobs {

    record AbortResult(boolean confirmed, String message) {
    }

    private final ProcessRunner runner;
    private final Path exportBinary;
    private final Path importBinary;
    private final Duration timeout;

    OracleDataPumpJobs(
            ProcessRunner runner,
            @Value("${dbbackup.oracle.export-path}") Path exportBinary,
            @Value("${dbbackup.oracle.import-path}") Path importBinary,
            @Value("${dbbackup.oracle.connect-timeout}") Duration timeout) {
        this.runner = runner;
        this.exportBinary = OracleBinaries.require(exportBinary, "dbbackup.oracle.export-path");
        this.importBinary = OracleBinaries.require(importBinary, "dbbackup.oracle.import-path");
        this.timeout = timeout.plusSeconds(10);
    }

    AbortResult abortExport(DatabaseConnection connection, String jobName) {
        return abort(exportBinary, connection, jobName);
    }

    AbortResult abortImport(DatabaseConnection connection, String jobName) {
        return abort(importBinary, connection, jobName);
    }

    private AbortResult abort(Path binary, DatabaseConnection connection, String jobName) {
        try {
            ProcessRunner.Result result = OracleClient.attachAndKill(
                    runner, binary, connection, jobName, timeout);
            String output = result.stdout() + "\n" + result.stderr();
            if (result.succeeded() || output.contains("ORA-31626")) {
                return new AbortResult(true, output.strip());
            }
            return new AbortResult(false, result.errorOutput());
        } catch (RuntimeException e) {
            return new AbortResult(false, e.getMessage());
        }
    }

    static String exportJob(java.util.UUID id) {
        return job("DBB_EXP_", id);
    }

    static String sqlJob(java.util.UUID id) {
        return job("DBB_SQL_", id);
    }

    static String importJob(java.util.UUID id) {
        return job("DBB_IMP_", id);
    }

    private static String job(String prefix, java.util.UUID id) {
        return prefix + id.toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
    }
}
