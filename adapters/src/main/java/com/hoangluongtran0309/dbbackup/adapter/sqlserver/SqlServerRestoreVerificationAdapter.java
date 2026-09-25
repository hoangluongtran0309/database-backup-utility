package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationPort;

/** Imports a BACPAC into one disposable SQL Server instance and checks every table. */
@Component
class SqlServerRestoreVerificationAdapter implements RestoreVerificationPort {
    private static final String DATABASE = "verification";
    private static final String WORK_DIRECTORY = "/tmp/dbbackup-verify";
    private static final String BACPAC = WORK_DIRECTORY + "/backup.bacpac";
    private static final String RESPONSE_FILE = WORK_DIRECTORY + "/target-password.rsp";
    private static final String SQLPACKAGE = "/opt/sqlpackage/sqlpackage";
    private static final String SQLCMD = "/opt/mssql-tools18/bin/sqlcmd";

    private final DockerVerificationSupport docker;
    private final boolean enabled;
    private final String image;
    private final Duration startupTimeout;
    private final Duration restoreTimeout;

    SqlServerRestoreVerificationAdapter(
            DockerVerificationSupport docker,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled,
            @Value("${dbbackup.verification.sqlserver-image:dbbackup-verification-sqlserver:2022}") String image,
            @Value("${dbbackup.verification.sqlserver-startup-timeout:5m}") Duration startupTimeout,
            @Value("${dbbackup.restore.timeout}") Duration restoreTimeout) {
        this.docker = docker;
        this.enabled = enabled;
        this.image = image;
        this.startupTimeout = startupTimeout;
        this.restoreTimeout = restoreTimeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.SQLSERVER;
    }

    @Override
    public boolean isAvailable() {
        return enabled && docker.available() && docker.imagePresent(image);
    }

    @Override
    public RestoreVerificationResult verify(UUID id, DatabaseTarget source, Path artifact) {
        String password = randomPassword();
        String container = null;
        RuntimeException failure = null;
        try {
            container = docker.start(engine(), id, image, Map.of(
                    "ACCEPT_EULA", "Y",
                    "MSSQL_PID", "Developer",
                    "MSSQL_SA_PASSWORD", password));
            Map<String, String> environment = Map.of("SQLCMDPASSWORD", password);
            docker.waitUntilReady(container, startupTimeout, environment,
                    SQLCMD, "-S", "localhost", "-U", "sa", "-N", "-C", "-b", "-Q", "SELECT 1");
            prepareFiles(container, artifact, password);
            ProcessRunner.Result imported = docker.execAsUser(container, restoreTimeout, "mssql",
                    Map.of("TMPDIR", WORK_DIRECTORY),
                    SqlPackageImportCommand.build(
                            SQLPACKAGE, BACPAC, "localhost", DATABASE, "sa", true,
                            Duration.ofSeconds(30), RESPONSE_FILE).toArray(String[]::new));
            DockerVerificationSupport.requireSuccess(imported, "SqlPackage verification import failed");
            DockerVerificationSupport.requireSuccess(docker.execAsUser(
                    container, Duration.ofSeconds(30), "mssql", "rm", "-f", RESPONSE_FILE),
                    "Could not remove the temporary SqlPackage response file");
            return healthCheck(container, environment);
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                docker.remove(engine(), id);
            } catch (RuntimeException cleanup) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    @Override
    public void abortInterrupted(UUID verificationId) {
        docker.remove(engine(), verificationId);
    }

    private void prepareFiles(String container, Path artifact, String password) {
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "mkdir", "-p", WORK_DIRECTORY),
                "Could not prepare SQL Server verification staging");
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "chown", "mssql:root", WORK_DIRECTORY),
                "Could not secure SQL Server verification staging");
        docker.copy(artifact, container, BACPAC, Duration.ofMinutes(2));
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "chown", "mssql:root", BACPAC),
                "Could not secure the verification BACPAC");
        String response = SqlPackageResponseFile.passwordArgument("TargetPassword", password)
                + System.lineSeparator();
        ProcessRunner.Result written = docker.feedAsUser(container, Duration.ofSeconds(30),
                new ByteArrayInputStream(response.getBytes(StandardCharsets.UTF_8)),
                "mssql", Map.of(), "sh", "-c", "umask 077; cat > " + RESPONSE_FILE);
        DockerVerificationSupport.requireSuccess(written,
                "Could not create the temporary SqlPackage response file");
    }

    private RestoreVerificationResult healthCheck(String container, Map<String, String> environment) {
        String script = """
                SET NOCOUNT ON;
                DBCC CHECKDB ([verification]) WITH NO_INFOMSGS, ALL_ERRORMSGS;
                DECLARE @sql nvarchar(max) = N'';
                SELECT @sql = @sql + N'SELECT TOP (1) 1 FROM '
                  + QUOTENAME(s.name) + N'.' + QUOTENAME(t.name) + N';'
                  + NCHAR(10)
                  FROM verification.sys.tables t
                  JOIN verification.sys.schemas s ON s.schema_id = t.schema_id
                  WHERE t.is_ms_shipped = 0 ORDER BY t.object_id;
                IF LEN(@sql) > 0 EXEC verification.sys.sp_executesql @sql;
                SELECT 'TABLE_COUNT=' + CONVERT(varchar(20), COUNT(*))
                  FROM verification.sys.tables WHERE is_ms_shipped = 0;
                """;
        ProcessRunner.Result checked = docker.execAsUser(container, Duration.ofMinutes(2), "mssql", environment,
                SQLCMD, "-S", "localhost", "-U", "sa", "-N", "-C", "-b",
                "-h", "-1", "-W", "-Q", script);
        DockerVerificationSupport.requireSuccess(checked, "SQL Server health check failed");
        int tables = taggedCount(checked.stdout(), "TABLE_COUNT=");
        return new RestoreVerificationResult(tables,
                "Restored successfully; DBCC CHECKDB passed and read %d SQL Server table(s)"
                        .formatted(tables));
    }

    private static int taggedCount(String output, String tag) {
        String value = output.lines().map(String::strip)
                .filter(line -> line.startsWith(tag)).reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("SQL Server health check did not return " + tag));
        try {
            return Integer.parseInt(value.substring(tag.length()).strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("SQL Server health check returned an invalid table count", e);
        }
    }

    private static String randomPassword() {
        return "Dbv1!" + UUID.randomUUID().toString().replace("-", "");
    }
}
