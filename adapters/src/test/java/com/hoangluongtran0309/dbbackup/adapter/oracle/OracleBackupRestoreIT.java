package com.hoangluongtran0309.dbbackup.adapter.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.SelinuxContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.oracle.OracleContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Exercises the production adapters against the real clients in Oracle Free. */
@Testcontainers
class OracleBackupRestoreIT {

    private static final String SOURCE = "SOURCE18";
    private static final String DESTINATION = "DEST18";
    private static final String SOURCE_PASSWORD = "SourcePass_18";
    private static final String DESTINATION_PASSWORD = "DestPass_18";
    private static final String DIRECTORY = "DBBACKUP_PUMP_DIR";
    private static final String SERVER_STAGING = "/opt/oracle/dpump";
    private static final Path STAGING = stagingDirectory();

    @Container
    static final OracleContainer ORACLE = oracleContainer();

    private static Path sqlplus;
    private static Path expdp;
    private static Path impdp;

    @BeforeAll
    static void prepareOracleAndClientWrappers() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE USER " + SOURCE + " IDENTIFIED BY \"" + SOURCE_PASSWORD
                    + "\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
            statement.executeUpdate("CREATE USER " + DESTINATION + " IDENTIFIED BY \"" + DESTINATION_PASSWORD
                    + "\" DEFAULT TABLESPACE USERS QUOTA UNLIMITED ON USERS");
            statement.executeUpdate("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + SOURCE);
            statement.executeUpdate("GRANT CREATE SESSION, CREATE TABLE, CREATE SEQUENCE TO " + DESTINATION);
            statement.executeUpdate("CREATE OR REPLACE DIRECTORY " + DIRECTORY + " AS '" + SERVER_STAGING + "'");
            statement.executeUpdate("GRANT READ, WRITE ON DIRECTORY " + DIRECTORY + " TO " + SOURCE);
            statement.executeUpdate("GRANT READ, WRITE ON DIRECTORY " + DIRECTORY + " TO " + DESTINATION);
        }
        try (Connection connection = userConnection(SOURCE, SOURCE_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE ITEMS (ID NUMBER CONSTRAINT ITEMS_PK PRIMARY KEY, "
                    + "LABEL NVARCHAR2(100) NOT NULL, CODE VARCHAR2(20) CONSTRAINT ITEMS_CODE_UQ UNIQUE)");
            statement.executeUpdate("CREATE INDEX ITEMS_LABEL_IDX ON ITEMS (LABEL)");
            statement.executeUpdate("CREATE SEQUENCE ITEMS_SEQ START WITH 100 INCREMENT BY 1");
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO ITEMS (ID, LABEL, CODE) VALUES (?, ?, ?)")) {
                insert.setInt(1, 1);
                insert.setNString(2, "Đà Nẵng — 東京");
                insert.setString(3, "unicode");
                insert.executeUpdate();
            }
        }
        try (Connection connection = userConnection(DESTINATION, DESTINATION_PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE ITEMS (ID NUMBER PRIMARY KEY, LABEL NVARCHAR2(100), CODE VARCHAR2(20))");
            statement.executeUpdate("INSERT INTO ITEMS VALUES (99, N'old destination row', 'old')");
            statement.executeUpdate("CREATE TABLE UNRELATED (ID NUMBER PRIMARY KEY, NOTE VARCHAR2(40))");
            statement.executeUpdate("INSERT INTO UNRELATED VALUES (1, 'keep me')");
        }

        sqlplus = wrapper("sqlplus");
        expdp = wrapper("expdp");
        impdp = wrapper("impdp");
    }

    @AfterAll
    static void removeWrapperDirectory() throws Exception {
        if (sqlplus != null) {
            Files.deleteIfExists(sqlplus);
            Files.deleteIfExists(expdp);
            Files.deleteIfExists(impdp);
            Files.deleteIfExists(sqlplus.getParent());
        }
        try (var paths = Files.list(STAGING)) {
            paths.forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // The container may own a deliberately broken-test artifact.
                }
            });
        }
        Files.deleteIfExists(STAGING);
    }

    @Test
    void testsSharedDirectoryBacksUpUnicodeAndRestoresIntoAnotherSchema() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        OracleDataPumpFiles files = new OracleDataPumpFiles(STAGING);
        DatabaseConnection source = connection(SOURCE, SOURCE_PASSWORD);
        DatabaseConnection destination = connection(DESTINATION, DESTINATION_PASSWORD);

        var connectionResult = new OracleCliConnectionTestAdapter(
                runner, files, sqlplus, Duration.ofSeconds(30)).test(source);
        assertThat(connectionResult.successful())
                .withFailMessage("Oracle connection probe failed: %s%n%s",
                        connectionResult.message(), sharedDirectoryDetails())
                .isTrue();

        OracleDataPumpJobs jobs = new OracleDataPumpJobs(
                runner, expdp, impdp, Duration.ofSeconds(30));
        OracleDataPumpBackupAdapter backup = new OracleDataPumpBackupAdapter(
                runner, files, jobs, expdp, Duration.ofMinutes(3));
        OracleDataPumpRestoreAdapter restore = new OracleDataPumpRestoreAdapter(
                runner, files, jobs, impdp, Duration.ofMinutes(4));
        Path artifact = STAGING.resolveSibling("oracle-roundtrip-" + UUID.randomUUID() + ".dmp");

        try {
            assertThat(backup.dumpTo(source, artifact, UUID.randomUUID())).isPositive();
            restore.restore(destination, SOURCE, artifact, UUID.randomUUID());

            assertThat(singleValue(DESTINATION, DESTINATION_PASSWORD,
                    "SELECT LABEL FROM ITEMS WHERE ID = 1")).isEqualTo("Đà Nẵng — 東京");
            assertThat(singleValue(DESTINATION, DESTINATION_PASSWORD,
                    "SELECT COUNT(*) FROM ITEMS WHERE ID = 99")).isEqualTo("0");
            assertThat(singleValue(DESTINATION, DESTINATION_PASSWORD,
                    "SELECT NOTE FROM UNRELATED WHERE ID = 1")).isEqualTo("keep me");
            assertThat(singleValue(DESTINATION, DESTINATION_PASSWORD,
                    "SELECT COUNT(*) FROM USER_SEQUENCES WHERE SEQUENCE_NAME = 'ITEMS_SEQ'"))
                    .isEqualTo("1");
            assertThat(singleValue(DESTINATION, DESTINATION_PASSWORD,
                    "SELECT COUNT(*) FROM USER_INDEXES WHERE INDEX_NAME = 'ITEMS_LABEL_IDX'"))
                    .isEqualTo("1");
            assertThat(singleValue(SOURCE, SOURCE_PASSWORD,
                    "SELECT LABEL FROM ITEMS WHERE ID = 1")).isEqualTo("Đà Nẵng — 東京");
        } finally {
            Files.deleteIfExists(artifact);
        }
        assertStagingEmpty();
    }

    @Test
    void corruptDumpFailsInPreflightAndDoesNotRunTheImport() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        OracleDataPumpFiles files = new OracleDataPumpFiles(STAGING);
        OracleDataPumpJobs jobs = new OracleDataPumpJobs(runner, expdp, impdp, Duration.ofSeconds(30));
        OracleDataPumpRestoreAdapter restore = new OracleDataPumpRestoreAdapter(
                runner, files, jobs, impdp, Duration.ofMinutes(2));
        Path artifact = STAGING.resolveSibling("oracle-corrupt-" + UUID.randomUUID() + ".dmp");
        Files.writeString(artifact, "not an Oracle Data Pump archive");

        Throwable failure;
        try {
            failure = catchThrowable(() -> restore.restore(
                    connection(DESTINATION, DESTINATION_PASSWORD), SOURCE, artifact, UUID.randomUUID()));
            assertThat(failure)
                    .isInstanceOf(RestoreFailedException.class)
                    .hasMessageContaining("preflight failed");
            assertThat(singleValue(DESTINATION, DESTINATION_PASSWORD,
                    "SELECT NOTE FROM UNRELATED WHERE ID = 1")).isEqualTo("keep me");
        } finally {
            Files.deleteIfExists(artifact);
        }
        assertStagingEmpty(failureMessage(failure));
    }

    @Test
    void connectionProbeReportsAMissingDirectoryGrantAndCleansItsProbe() throws Exception {
        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("REVOKE WRITE ON DIRECTORY " + DIRECTORY + " FROM " + SOURCE);
        }
        try {
            OracleCliConnectionTestAdapter adapter = new OracleCliConnectionTestAdapter(
                    new ProcessRunner(), new OracleDataPumpFiles(STAGING), sqlplus, Duration.ofSeconds(30));
            assertThat(adapter.test(connection(SOURCE, SOURCE_PASSWORD)).successful()).isFalse();
            assertStagingEmpty();
        } finally {
            try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
                statement.executeUpdate("GRANT WRITE ON DIRECTORY " + DIRECTORY + " TO " + SOURCE);
            }
        }
    }

    private static DatabaseConnection connection(String username, String password) {
        return new DatabaseConnection(
                DatabaseEngine.ORACLE, "127.0.0.1", 1521, ORACLE.getDatabaseName(),
                username, password, null, DIRECTORY);
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(), "system", ORACLE.getPassword());
    }

    private static Connection userConnection(String username, String password) throws Exception {
        return DriverManager.getConnection(ORACLE.getJdbcUrl(), username, password);
    }

    private static String singleValue(String username, String password, String sql) throws Exception {
        try (Connection connection = userConnection(username, password);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Path wrapper(String client) throws Exception {
        var locate = ORACLE.execInContainer("bash", "-lc",
                "command -v " + client + " || find /opt/oracle -type f -name " + client + " | head -n 1");
        assertThat(locate.getExitCode()).isZero();
        String clientPath = locate.getStdout().strip();
        assertThat(clientPath).isNotBlank();
        Path directory = sqlplus == null
                ? Files.createTempDirectory("dbbackup-oracle-client-wrappers-")
                : sqlplus.getParent();
        Path wrapper = directory.resolve(client);
        Files.writeString(wrapper, """
                #!/bin/sh
                docker exec -i -e TWO_TASK="$TWO_TASK" %s %s "$@"
                status=$?
                docker exec %s sh -c 'chmod a+rw %s/dbbackup-* 2>/dev/null || true'
                exit "$status"
                """.formatted(ORACLE.getContainerId(), clientPath, ORACLE.getContainerId(), SERVER_STAGING));
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwx------"));
        return wrapper;
    }

    private static Path stagingDirectory() {
        try {
            Path path = Files.createTempDirectory("dbbackup-oracle-datapump-");
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwxrwxrwx"));
            return path.toRealPath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static OracleContainer oracleContainer() {
        OracleContainer container = new OracleContainer("gvenzl/oracle-free:23-slim-faststart")
                .withStartupTimeout(Duration.ofMinutes(8));
        container.addFileSystemBind(
                STAGING.toString(), SERVER_STAGING, BindMode.READ_WRITE, SelinuxContext.SHARED);
        return container;
    }

    private static void assertStagingEmpty() throws Exception {
        assertStagingEmpty("Oracle staging directory was not cleaned");
    }

    private static void assertStagingEmpty(String message) throws Exception {
        try (var paths = Files.list(STAGING)) {
            assertThat(paths.toList()).withFailMessage(message).isEmpty();
        }
    }

    private static String sharedDirectoryDetails() {
        try {
            var result = ORACLE.execInContainer(
                    "sh", "-c", "id; ls -lad " + SERVER_STAGING + "; ls -la " + SERVER_STAGING);
            return "Configured binds: " + ORACLE.getBinds() + "\nContainer view:\n" + result.getStdout()
                    + result.getStderr();
        } catch (Exception e) {
            return "Could not inspect shared directory: " + e.getMessage();
        }
    }

    private static String failureMessage(Throwable failure) {
        return failure == null ? "Restore did not fail as expected" : failure.getMessage();
    }
}
