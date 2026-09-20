package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Preflights and imports one Oracle schema-mode Data Pump dump. */
@Component
@ConditionalOnProperty(name = "dbbackup.oracle.enabled", havingValue = "true")
class OracleDataPumpRestoreAdapter implements LogicalRestorePort {

    private final ProcessRunner runner;
    private final Path binary;
    private final OracleDataPumpFiles files;
    private final OracleDataPumpJobs jobs;
    private final Duration timeout;

    OracleDataPumpRestoreAdapter(
            ProcessRunner runner,
            OracleDataPumpFiles files,
            OracleDataPumpJobs jobs,
            @Value("${dbbackup.oracle.import-path}") Path binary,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
        this.runner = runner;
        this.files = files;
        this.jobs = jobs;
        this.binary = OracleBinaries.require(binary, "dbbackup.oracle.import-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ORACLE;
    }

    @Override
    public void restore(DatabaseConnection connection, String sourceNamespace, Path artifact) {
        restore(connection, sourceNamespace, artifact, UUID.randomUUID());
    }

    @Override
    public void restore(
            DatabaseConnection connection, String sourceNamespace, Path artifact, UUID operationId) {
        Path staged = files.restoreDump(operationId);
        Path sqlFile = files.sqlFile(operationId);
        String sqlJob = OracleDataPumpJobs.sqlJob(operationId);
        String importJob = OracleDataPumpJobs.importJob(operationId);
        try {
            files.stageForImport(artifact, staged);
            run(connection, preflightCommand(connection, sourceNamespace, staged, sqlFile, sqlJob),
                    "The Oracle Data Pump archive preflight failed");
            if (!files.isReadableRegularFile(sqlFile)) {
                throw new RestoreFailedException(
                        "Oracle completed the preflight but its SQLFILE is not visible at " + sqlFile);
            }
            files.delete(sqlFile);
            run(connection, importCommand(connection, sourceNamespace, staged, importJob),
                    "impdp failed");
            files.delete(staged);
        } catch (RestoreFailedException e) {
            throw cleanupFailure(connection, operationId, staged, sqlFile, e);
        } catch (RuntimeException e) {
            throw cleanupFailure(connection, operationId, staged, sqlFile,
                    new RestoreFailedException(e.getMessage(), e));
        }
    }

    @Override
    public void abortInterrupted(UUID operationId, Supplier<DatabaseConnection> connectionSupplier) {
        DatabaseConnection connection = connectionSupplier.get();
        AbortSummary abort = abort(connection, operationId);
        if (!abort.confirmed()) {
            throw new IllegalStateException(abort.message());
        }
        files.delete(files.sqlFile(operationId));
        files.delete(files.restoreDump(operationId));
    }

    private void run(DatabaseConnection connection, List<String> command, String prefix) {
        ProcessRunner.Result result;
        try {
            result = OracleClient.run(runner, binary, connection, command, timeout);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new RestoreFailedException(
                    "%s (exit %d): %s".formatted(prefix, result.exitCode(), result.errorOutput()));
        }
    }

    List<String> preflightCommand(
            DatabaseConnection connection,
            String sourceNamespace,
            Path staged,
            Path sqlFile,
            String jobName) {
        List<String> command = baseCommand(connection, sourceNamespace, staged, jobName);
        addSchemaRemap(command, connection, sourceNamespace);
        command.add("SQLFILE=" + sqlFile.getFileName());
        return List.copyOf(command);
    }

    List<String> importCommand(
            DatabaseConnection connection,
            String sourceNamespace,
            Path staged,
            String jobName) {
        List<String> command = baseCommand(connection, sourceNamespace, staged, jobName);
        command.add("TABLE_EXISTS_ACTION=REPLACE");
        command.add("TRANSFORM=OID:N");
        command.add("TRANSFORM=SEGMENT_ATTRIBUTES:N");
        addSchemaRemap(command, connection, sourceNamespace);
        return List.copyOf(command);
    }

    private static void addSchemaRemap(
            List<String> command, DatabaseConnection connection, String sourceNamespace) {
        if (!sourceNamespace.equals(connection.username())) {
            command.add("REMAP_SCHEMA=" + sourceNamespace + ":" + connection.username());
        }
    }

    private static List<String> baseCommand(
            DatabaseConnection connection, String sourceNamespace, Path staged, String jobName) {
        return new ArrayList<>(List.of(
                "DIRECTORY=" + connection.dataPumpDirectory(),
                "DUMPFILE=" + staged.getFileName(),
                "SCHEMAS=" + sourceNamespace,
                "JOB_NAME=" + jobName,
                "NOLOGFILE=YES"));
    }

    private RestoreFailedException cleanupFailure(
            DatabaseConnection connection,
            UUID operationId,
            Path staged,
            Path sqlFile,
            RestoreFailedException failure) {
        AbortSummary abort = abort(connection, operationId);
        if (!abort.confirmed()) {
            return new RestoreFailedException(
                    "%s (%s; staging was preserved)".formatted(failure.getMessage(), abort.message()), failure);
        }
        try {
            files.delete(sqlFile);
            files.delete(staged);
        } catch (RuntimeException cleanup) {
            failure.addSuppressed(cleanup);
        }
        return failure;
    }

    private AbortSummary abort(DatabaseConnection connection, UUID operationId) {
        OracleDataPumpJobs.AbortResult sql = jobs.abortImport(connection, OracleDataPumpJobs.sqlJob(operationId));
        OracleDataPumpJobs.AbortResult actual =
                jobs.abortImport(connection, OracleDataPumpJobs.importJob(operationId));
        boolean confirmed = sql.confirmed() && actual.confirmed();
        return new AbortSummary(confirmed,
                confirmed ? "" : "Oracle Data Pump jobs could not be confirmed stopped: preflight=%s, import=%s"
                        .formatted(sql.message(), actual.message()));
    }

    private record AbortSummary(boolean confirmed, String message) {
    }
}
