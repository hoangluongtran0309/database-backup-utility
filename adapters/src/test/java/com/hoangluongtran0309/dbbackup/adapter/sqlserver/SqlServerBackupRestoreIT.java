package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mssqlserver.MSSQLServerContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Exercises the production SQL Server adapters against real clients and SQL Server 2022. */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlServerBackupRestoreIT {

    private static final String SOURCE = "source20";
    private static final String EMPTY = "empty20";
    private static final String MISSING = "missing20";
    private static final String NONEMPTY = "nonempty20";
    private static final String PASSWORD = "S0urce p\"a\\ss!2026";

    @Container
    static final MSSQLServerContainer SQL_SERVER = new MSSQLServerContainer(
            "mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense()
            .withPassword(PASSWORD);

    private final ProcessRunner runner = new ProcessRunner();
    private Path sqlpackage;
    private Path sqlcmd;
    private SqlServerTemporaryFiles temporaryFiles;

    @BeforeAll
    void prepareDatabases() throws Exception {
        sqlpackage = Path.of(System.getenv().getOrDefault(
                "SQLPACKAGE_PATH", "/opt/sqlpackage/sqlpackage"));
        sqlcmd = Path.of(System.getenv().getOrDefault(
                "SQLCMD_PATH", "/opt/mssql-tools18/bin/sqlcmd"));
        temporaryFiles = new SqlServerTemporaryFiles(Files.createTempDirectory("dbbackup-sqlserver-it-"));

        try (Connection connection = adminConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE " + SOURCE);
            statement.executeUpdate("CREATE DATABASE " + EMPTY);
            statement.executeUpdate("CREATE DATABASE " + NONEMPTY);
        }
        try (Connection connection = databaseConnection(SOURCE); Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE dbo.items (
                        id int IDENTITY(10, 5) PRIMARY KEY,
                        label nvarchar(100) NOT NULL,
                        code varchar(20) NOT NULL UNIQUE,
                        CONSTRAINT ck_items_code CHECK (LEN(code) > 0)
                    )
                    """);
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO dbo.items (label, code) VALUES (?, ?)")) {
                insert.setNString(1, "Đà Nẵng — 東京");
                insert.setString(2, "unicode");
                insert.executeUpdate();
            }
        }
        try (Connection connection = databaseConnection(NONEMPTY); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE dbo.sentinel (id int PRIMARY KEY, note varchar(50) NOT NULL)");
            statement.executeUpdate("INSERT INTO dbo.sentinel VALUES (1, 'keep me')");
        }
    }

    @Test
    void probesExportsAndImportsIntoEmptyAndMissingDatabases() throws Exception {
        DatabaseConnection source = target(SOURCE, PASSWORD);
        SqlServerCliConnectionTestAdapter connectionTest = new SqlServerCliConnectionTestAdapter(
                runner, sqlcmd, Duration.ofSeconds(15), true);
        assertThat(connectionTest.test(source).successful()).isTrue();

        Path artifact = Files.createTempFile("source20-", ".bacpac");
        Files.delete(artifact);
        SqlServerBacpacBackupAdapter backup = new SqlServerBacpacBackupAdapter(
                runner, sqlpackage, Duration.ofSeconds(15), Duration.ofMinutes(5), true, temporaryFiles);
        assertThat(backup.dumpTo(source, artifact)).isPositive();
        assertThat(artifact).isNotEmptyFile();

        SqlServerBacpacRestoreAdapter restore = new SqlServerBacpacRestoreAdapter(
                runner, sqlpackage, Duration.ofSeconds(15), Duration.ofMinutes(5), true, temporaryFiles);
        restore.restore(target(EMPTY, PASSWORD), SOURCE, artifact);
        restore.restore(target(MISSING, PASSWORD), SOURCE, artifact);

        assertImported(EMPTY);
        assertImported(MISSING);
    }

    @Test
    void refusesANonEmptyDestinationWithoutChangingIt() throws Exception {
        Path artifact = Files.createTempFile("source20-nonempty-", ".bacpac");
        Files.delete(artifact);
        new SqlServerBacpacBackupAdapter(
                runner, sqlpackage, Duration.ofSeconds(15), Duration.ofMinutes(5), true, temporaryFiles)
                .dumpTo(target(SOURCE, PASSWORD), artifact);

        SqlServerBacpacRestoreAdapter restore = new SqlServerBacpacRestoreAdapter(
                runner, sqlpackage, Duration.ofSeconds(15), Duration.ofMinutes(5), true, temporaryFiles);
        assertThatThrownBy(() -> restore.restore(target(NONEMPTY, PASSWORD), SOURCE, artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("must be missing or contain no user-defined objects");

        try (Connection connection = databaseConnection(NONEMPTY);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT note FROM dbo.sentinel WHERE id = 1")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getString(1)).isEqualTo("keep me");
        }
    }

    @Test
    void badCredentialsUntrustedTlsAndInvalidBacpacAllFailClearly() throws Exception {
        assertThat(new SqlServerCliConnectionTestAdapter(
                runner, sqlcmd, Duration.ofSeconds(15), true)
                .test(target(SOURCE, "Wr0ng-password!"))
                .successful()).isFalse();
        assertThat(new SqlServerCliConnectionTestAdapter(
                runner, sqlcmd, Duration.ofSeconds(15), false)
                .test(target(SOURCE, PASSWORD))
                .successful()).isFalse();

        Path invalid = Files.createTempFile("invalid-", ".bacpac");
        Files.writeString(invalid, "not a BACPAC");
        SqlServerBacpacRestoreAdapter restore = new SqlServerBacpacRestoreAdapter(
                runner, sqlpackage, Duration.ofSeconds(15), Duration.ofMinutes(5), true, temporaryFiles);
        assertThatThrownBy(() -> restore.restore(target("invalid20", PASSWORD), SOURCE, invalid))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("SqlPackage import failed");
    }

    private static DatabaseConnection target(String database, String password) {
        return new DatabaseConnection(
                DatabaseEngine.SQLSERVER,
                SQL_SERVER.getHost(),
                SQL_SERVER.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT),
                database,
                SQL_SERVER.getUsername(),
                password);
    }

    private static Connection adminConnection() throws Exception {
        return DriverManager.getConnection(
                jdbcUrl("master"), SQL_SERVER.getUsername(), SQL_SERVER.getPassword());
    }

    private static Connection databaseConnection(String database) throws Exception {
        return DriverManager.getConnection(
                jdbcUrl(database), SQL_SERVER.getUsername(), SQL_SERVER.getPassword());
    }

    private static String jdbcUrl(String database) {
        return "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=true;trustServerCertificate=true"
                .formatted(
                        SQL_SERVER.getHost(),
                        SQL_SERVER.getMappedPort(MSSQLServerContainer.MS_SQL_SERVER_PORT),
                        database);
    }

    private static void assertImported(String database) throws Exception {
        try (Connection connection = databaseConnection(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT id, label, code FROM dbo.items ORDER BY id")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("id")).isEqualTo(10);
            assertThat(result.getNString("label")).isEqualTo("Đà Nẵng — 東京");
            assertThat(result.getString("code")).isEqualTo("unicode");
            assertThat(result.next()).isFalse();
        }
        try (Connection connection = databaseConnection(database);
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO dbo.items (label, code) VALUES (N'next', 'next')");
            try (ResultSet result = statement.executeQuery("SELECT id FROM dbo.items WHERE code = 'next'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getInt(1)).isEqualTo(15);
            }
            assertThatThrownBy(() -> statement.executeUpdate(
                    "INSERT INTO dbo.items (label, code) VALUES (N'duplicate', 'next')"))
                    .satisfies(error -> assertThat(error.getMessage()).containsIgnoringCase("unique"));
        }
    }
}
