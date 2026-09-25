package com.hoangluongtran0309.dbbackup.adapter.oracle;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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

/** Restores a schema-mode Data Pump archive into one disposable Oracle Free instance. */
@Component
class OracleRestoreVerificationAdapter implements RestoreVerificationPort {
    private static final String SERVICE = "FREEPDB1";
    private static final String DESTINATION_SCHEMA = "DBBVERIFY";
    private static final String DIRECTORY_NAME = "DBBACKUP_VERIFY_DIR";
    private static final String DIRECTORY_PATH = "/opt/oracle/dbbackup-verify";
    private static final String DUMP_PATH = DIRECTORY_PATH + "/backup.dmp";
    private static final String SQL_FILE = "preflight.sql";

    private final DockerVerificationSupport docker;
    private final boolean enabled;
    private final String image;
    private final Duration startupTimeout;
    private final Duration restoreTimeout;

    OracleRestoreVerificationAdapter(
            DockerVerificationSupport docker,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled,
            @Value("${dbbackup.verification.oracle-image:gvenzl/oracle-free:23-slim-faststart}") String image,
            @Value("${dbbackup.verification.oracle-startup-timeout:10m}") Duration startupTimeout,
            @Value("${dbbackup.restore.timeout}") Duration restoreTimeout) {
        this.docker = docker;
        this.enabled = enabled;
        this.image = image;
        this.startupTimeout = startupTimeout;
        this.restoreTimeout = restoreTimeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.ORACLE;
    }

    @Override
    public boolean isAvailable() {
        return enabled && docker.available();
    }

    @Override
    public RestoreVerificationResult verify(UUID id, DatabaseTarget source, Path artifact) {
        String systemPassword = randomPassword();
        String appPassword = randomPassword();
        String container = null;
        RuntimeException failure = null;
        try {
            container = docker.start(engine(), id, image, Map.of(
                    "ORACLE_PASSWORD", systemPassword,
                    "APP_USER", DESTINATION_SCHEMA,
                    "APP_USER_PASSWORD", appPassword));
            docker.waitUntilReady(container, startupTimeout, Map.of(), "/opt/oracle/healthcheck.sh");
            prepareDump(container, artifact, systemPassword);
            runDataPump(container, systemPassword, OracleDataPumpImportCommand.preflight(
                    DIRECTORY_NAME, Path.of(DUMP_PATH).getFileName().toString(), source.getUsername(),
                    DESTINATION_SCHEMA, OracleDataPumpJobs.sqlJob(id), SQL_FILE, true),
                    "Oracle Data Pump archive preflight failed");
            runDataPump(container, systemPassword, OracleDataPumpImportCommand.importDump(
                    DIRECTORY_NAME, Path.of(DUMP_PATH).getFileName().toString(), source.getUsername(),
                    DESTINATION_SCHEMA, OracleDataPumpJobs.importJob(id), true),
                    "Oracle Data Pump import failed");
            return healthCheck(container, systemPassword);
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

    private void prepareDump(String container, Path artifact, String systemPassword) {
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "mkdir", "-p", DIRECTORY_PATH),
                "Could not prepare the Oracle verification directory");
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "chown", "oracle:oinstall", DIRECTORY_PATH),
                "Could not secure the Oracle verification directory");
        docker.copy(artifact, container, DUMP_PATH, Duration.ofMinutes(2));
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "chown", "oracle:oinstall", DUMP_PATH),
                "Could not secure the Oracle verification archive");
        DockerVerificationSupport.requireSuccess(docker.execAsUser(
                container, Duration.ofSeconds(30), "root", "chmod", "600", DUMP_PATH),
                "Could not secure the Oracle verification archive");
        String script = """
                WHENEVER SQLERROR EXIT SQL.SQLCODE
                CONNECT SYSTEM/\"%s\"@//localhost:1521/%s
                CREATE OR REPLACE DIRECTORY %s AS '%s';
                EXIT
                """.formatted(systemPassword, SERVICE, DIRECTORY_NAME, DIRECTORY_PATH);
        requireSuccess(feed(container, Duration.ofMinutes(1), script, Map.of(),
                "sqlplus", "-L", "-S", "/NOLOG"), "Could not configure Oracle Data Pump staging");
    }

    private void runDataPump(
            String container, String password, List<String> parameters, String failureMessage) {
        List<String> command = new java.util.ArrayList<>();
        command.add("impdp");
        command.add("SYSTEM");
        command.addAll(parameters);
        requireSuccess(feed(container, restoreTimeout, password + System.lineSeparator(),
                Map.of("TWO_TASK", "//localhost:1521/" + SERVICE), command.toArray(String[]::new)),
                failureMessage);
    }

    private RestoreVerificationResult healthCheck(String container, String systemPassword) {
        String script = """
                SET HEADING OFF FEEDBACK OFF PAGESIZE 0 VERIFY OFF ECHO OFF
                WHENEVER SQLERROR EXIT SQL.SQLCODE
                CONNECT SYSTEM/\"%s\"@//localhost:1521/%s
                DECLARE
                  value NUMBER;
                BEGIN
                  FOR item IN (SELECT table_name FROM dba_tables WHERE owner = '%s') LOOP
                    BEGIN
                      EXECUTE IMMEDIATE 'SELECT 1 FROM %s."' || REPLACE(item.table_name, '"', '""') || '" WHERE ROWNUM = 1' INTO value;
                    EXCEPTION WHEN NO_DATA_FOUND THEN NULL;
                    END;
                  END LOOP;
                END;
                /
                SELECT 'TABLE_COUNT=' || COUNT(*) FROM dba_tables WHERE owner = '%s';
                SELECT 'INVALID_COUNT=' || COUNT(*) FROM dba_objects
                  WHERE owner = '%s' AND status = 'INVALID' AND generated = 'N';
                EXIT
                """.formatted(systemPassword, SERVICE, DESTINATION_SCHEMA, DESTINATION_SCHEMA,
                DESTINATION_SCHEMA, DESTINATION_SCHEMA);
        ProcessRunner.Result checked = feed(container, Duration.ofMinutes(2), script, Map.of(),
                "sqlplus", "-L", "-S", "/NOLOG");
        requireSuccess(checked, "Oracle health check failed");
        int tables = taggedCount(checked.stdout(), "TABLE_COUNT=");
        int invalid = taggedCount(checked.stdout(), "INVALID_COUNT=");
        if (invalid != 0) {
            throw new IllegalStateException(
                    "Oracle health check found %d invalid user object(s)".formatted(invalid));
        }
        return new RestoreVerificationResult(tables,
                "Restored successfully, read %d Oracle table(s), and found no invalid user objects"
                        .formatted(tables));
    }

    private ProcessRunner.Result feed(
            String container, Duration timeout, String input,
            Map<String, String> environment, String... command) {
        return docker.feed(container, timeout,
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), environment, command);
    }

    private static void requireSuccess(ProcessRunner.Result result, String message) {
        DockerVerificationSupport.requireSuccess(result, message);
    }

    private static int taggedCount(String output, String tag) {
        String value = output.lines().map(String::strip)
                .filter(line -> line.startsWith(tag)).reduce((first, second) -> second)
                .orElseThrow(() -> new IllegalStateException("Oracle health check did not return " + tag));
        try {
            return Integer.parseInt(value.substring(tag.length()).strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Oracle health check returned an invalid count", e);
        }
    }

    private static String randomPassword() {
        return "Dbv1!" + UUID.randomUUID().toString().replace("-", "");
    }
}
