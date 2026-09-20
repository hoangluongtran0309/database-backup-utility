package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/** Produces one schema-mode Oracle Data Pump dump through shared staging. */
@Component
@ConditionalOnProperty(name = "dbbackup.oracle.enabled", havingValue = "true")
class OracleDataPumpBackupAdapter implements LogicalBackupPort {

    private final ProcessRunner runner;
    private final Path binary;
    private final OracleDataPumpFiles files;
    private final OracleDataPumpJobs jobs;
    private final Duration timeout;

    OracleDataPumpBackupAdapter(
            ProcessRunner runner,
            OracleDataPumpFiles files,
            OracleDataPumpJobs jobs,
            @Value("${dbbackup.oracle.export-path}") Path binary,
            @Value("${dbbackup.backup.timeout}") Duration timeout) {
        this.runner = runner;
        this.files = files;
        this.jobs = jobs;
        this.binary = OracleBinaries.require(binary, "dbbackup.oracle.export-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ORACLE;
    }

    @Override
    public String artifactSuffix() {
        return ".dmp";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        return dumpTo(connection, destination, UUID.randomUUID());
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination, UUID operationId) {
        Path staged = files.backupDump(operationId);
        String jobName = OracleDataPumpJobs.exportJob(operationId);
        try {
            ProcessRunner.Result result = OracleClient.run(
                    runner, binary, connection,
                    command(connection, staged, jobName), timeout);
            if (!result.succeeded()) {
                throw failed(connection, staged, destination, jobName,
                        "expdp exited with %d: %s".formatted(result.exitCode(), result.errorOutput()));
            }
            files.copyFromDataPump(staged, destination);
            long size = files.size(destination);
            files.delete(staged);
            return size;
        } catch (BackupFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw failed(connection, staged, destination, jobName, e.getMessage());
        }
    }

    @Override
    public void abortInterrupted(UUID operationId, Supplier<DatabaseConnection> connectionSupplier) {
        DatabaseConnection connection = connectionSupplier.get();
        String jobName = OracleDataPumpJobs.exportJob(operationId);
        OracleDataPumpJobs.AbortResult aborted = jobs.abortExport(connection, jobName);
        if (!aborted.confirmed()) {
            throw new IllegalStateException(
                    "Could not confirm that Oracle Data Pump job %s stopped: %s"
                            .formatted(jobName, aborted.message()));
        }
        files.delete(files.backupDump(operationId));
    }

    List<String> command(DatabaseConnection connection, Path staged, String jobName) {
        return List.of(
                "DIRECTORY=" + connection.dataPumpDirectory(),
                "DUMPFILE=" + staged.getFileName(),
                "SCHEMAS=" + connection.username(),
                "JOB_NAME=" + jobName,
                "NOLOGFILE=YES");
    }

    private BackupFailedException failed(
            DatabaseConnection connection, Path staged, Path destination, String jobName, String message) {
        OracleDataPumpJobs.AbortResult aborted = jobs.abortExport(connection, jobName);
        deleteDestination(destination);
        if (aborted.confirmed()) {
            try {
                files.delete(staged);
            } catch (RuntimeException cleanup) {
                return new BackupFailedException(message + " (staging cleanup failed: " + cleanup.getMessage() + ")");
            }
            return new BackupFailedException(message);
        }
        return new BackupFailedException(
                "%s (Oracle job %s could not be confirmed stopped: %s; staging was preserved)"
                        .formatted(message, jobName, aborted.message()));
    }

    private static void deleteDestination(Path destination) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException ignored) {
            // The primary failure and durable Oracle job state are more useful here.
        }
    }
}
