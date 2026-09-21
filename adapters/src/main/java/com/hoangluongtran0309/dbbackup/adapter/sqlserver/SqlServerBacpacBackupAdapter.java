package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/** Exports one SQL Server database, schema and data, to a BACPAC. */
@Component
@ConditionalOnProperty(name = "dbbackup.sqlserver.enabled", havingValue = "true")
class SqlServerBacpacBackupAdapter implements LogicalBackupPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final Duration timeout;
    private final boolean trustServerCertificate;
    private final SqlServerTemporaryFiles temporaryFiles;

    SqlServerBacpacBackupAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.sqlserver.sqlpackage-path}") Path binary,
            @Value("${dbbackup.sqlserver.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.backup.timeout}") Duration timeout,
            @Value("${dbbackup.sqlserver.trust-server-certificate}") boolean trustServerCertificate,
            SqlServerTemporaryFiles temporaryFiles) {
        this.processRunner = processRunner;
        this.binary = SqlServerBinaries.require(binary, "dbbackup.sqlserver.sqlpackage-path");
        this.connectTimeout = connectTimeout;
        this.timeout = timeout;
        this.trustServerCertificate = trustServerCertificate;
        this.temporaryFiles = temporaryFiles;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.SQLSERVER;
    }

    @Override
    public String artifactSuffix() {
        return ".bacpac";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        try {
            return temporaryFiles.use(temporaryDirectory -> SqlPackageResponseFile.use(
                    "SourcePassword", connection.password(), responseFile -> {
                        ProcessRunner.Result result = processRunner.run(
                                command(connection, destination, temporaryDirectory, responseFile),
                                Map.of("TMPDIR", temporaryDirectory.toString()),
                                timeout);
                        if (!result.succeeded()) {
                            throw new BackupFailedException(
                                    "SqlPackage export failed (exit %d): %s"
                                            .formatted(result.exitCode(), result.errorOutput()));
                        }
                        try {
                            return Files.size(destination);
                        } catch (IOException e) {
                            throw new BackupFailedException("Could not read the completed BACPAC: " + e.getMessage());
                        }
                    }));
        } catch (ProcessRunner.ProcessFailedException e) {
            throw failed(destination, e.getMessage(), e);
        } catch (BackupFailedException e) {
            throw failed(destination, e.getMessage(), e);
        } catch (RuntimeException e) {
            throw failed(destination, e.getMessage(), e);
        }
    }

    List<String> command(
            DatabaseConnection connection,
            Path destination,
            Path temporaryDirectory,
            Path responseFile) {
        return List.copyOf(new ArrayList<>(List.of(
                binary.toString(),
                "/Action:Export",
                "/TargetFile:" + destination,
                "/SourceServerName:" + SqlServerCliConnectionTestAdapter.server(connection),
                "/SourceDatabaseName:" + connection.database(),
                "/SourceUser:" + connection.username(),
                "/SourceEncryptConnection:True",
                "/SourceTrustServerCertificate:" + trustServerCertificate,
                "/SourceTimeout:" + Math.max(1, connectTimeout.toSeconds()),
                "/p:CommandTimeout=0",
                "/p:LongRunningCommandTimeout=0",
                "/p:VerifyExtraction=True",
                "/p:TempDirectoryForTableData=" + temporaryDirectory,
                "@" + responseFile)));
    }

    private static BackupFailedException failed(Path destination, String message, Throwable cause) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException cleanup) {
            cause.addSuppressed(cleanup);
            return new BackupFailedException(
                    "%s (and the partial file '%s' could not be removed: %s)"
                            .formatted(message, destination, cleanup.getMessage()), cause);
        }
        return new BackupFailedException(message, cause);
    }
}
