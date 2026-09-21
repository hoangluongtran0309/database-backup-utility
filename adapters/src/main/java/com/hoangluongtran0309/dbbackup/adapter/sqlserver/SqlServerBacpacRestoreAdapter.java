package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Imports a BACPAC into a missing or empty SQL Server database. */
@Component
@ConditionalOnProperty(name = "dbbackup.sqlserver.enabled", havingValue = "true")
class SqlServerBacpacRestoreAdapter implements LogicalRestorePort {

    private static final String EMPTY_DESTINATION =
            "SQL Server destination database must be missing or contain no user-defined objects";

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration connectTimeout;
    private final Duration timeout;
    private final boolean trustServerCertificate;
    private final SqlServerTemporaryFiles temporaryFiles;

    SqlServerBacpacRestoreAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.sqlserver.sqlpackage-path}") Path binary,
            @Value("${dbbackup.sqlserver.connect-timeout}") Duration connectTimeout,
            @Value("${dbbackup.restore.timeout}") Duration timeout,
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
    public void restore(DatabaseConnection connection, String sourceNamespace, Path artifact) {
        if (!Files.isReadable(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is missing or unreadable".formatted(artifact));
        }
        try {
            temporaryFiles.use(temporaryDirectory -> SqlPackageResponseFile.use(
                    "TargetPassword", connection.password(), responseFile -> {
                        ProcessRunner.Result result = processRunner.run(
                                command(connection, artifact, responseFile),
                                Map.of("TMPDIR", temporaryDirectory.toString()),
                                timeout);
                        if (!result.succeeded()) {
                            throw new RestoreFailedException(
                                    "SqlPackage import failed; %s (exit %d): %s"
                                            .formatted(EMPTY_DESTINATION, result.exitCode(), result.errorOutput()));
                        }
                        return null;
                    }));
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        } catch (RestoreFailedException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RestoreFailedException("SqlPackage import could not run: " + e.getMessage(), e);
        }
    }

    List<String> command(DatabaseConnection connection, Path artifact, Path responseFile) {
        return List.of(
                binary.toString(),
                "/Action:Import",
                "/SourceFile:" + artifact,
                "/TargetServerName:" + SqlServerCliConnectionTestAdapter.server(connection),
                "/TargetDatabaseName:" + connection.database(),
                "/TargetUser:" + connection.username(),
                "/TargetEncryptConnection:True",
                "/TargetTrustServerCertificate:" + trustServerCertificate,
                "/TargetTimeout:" + Math.max(1, connectTimeout.toSeconds()),
                "/p:CommandTimeout=0",
                "/p:LongRunningCommandTimeout=0",
                "@" + responseFile);
    }
}
