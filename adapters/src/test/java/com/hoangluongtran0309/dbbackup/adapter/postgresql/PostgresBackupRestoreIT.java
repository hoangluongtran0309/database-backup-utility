package com.hoangluongtran0309.dbbackup.adapter.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

/** Real PostgreSQL plus the same host client binaries used in production. */
@Testcontainers
class PostgresBackupRestoreIT {

    private static final Path PSQL = Path.of("/usr/bin/psql");
    private static final Path PG_DUMP = Path.of("/usr/bin/pg_dump");
    private static final Path PG_RESTORE = Path.of("/usr/bin/pg_restore");
    private static final Pattern CLIENT_MAJOR = Pattern.compile("PostgreSQL\\) (\\d+)");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            "postgres:" + clientMajor() + "-alpine")
            .withDatabaseName("shop_source")
            .withUsername("backup")
            .withPassword("s3cr3t");

    @TempDir
    Path artifacts;

    private PostgresCliConnectionTestAdapter connectionTest;
    private PostgresDumpBackupAdapter backup;
    private PostgresRestoreAdapter restore;

    @BeforeAll
    static void requireClientBinaries() {
        assertThat(PSQL).isExecutable();
        assertThat(PG_DUMP).isExecutable();
        assertThat(PG_RESTORE).isExecutable();
    }

    @BeforeEach
    void setUp() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        connectionTest = new PostgresCliConnectionTestAdapter(runner, PSQL, Duration.ofSeconds(10));
        backup = new PostgresDumpBackupAdapter(
                runner, PG_DUMP, Duration.ofSeconds(10), Duration.ofMinutes(2));
        restore = new PostgresRestoreAdapter(
                runner, PG_RESTORE, Duration.ofSeconds(10), Duration.ofMinutes(2));

        execute("shop_source", """
                DROP TABLE IF EXISTS widgets;
                CREATE TABLE widgets (id integer PRIMARY KEY, name text NOT NULL);
                INSERT INTO widgets VALUES (1, 'alpha'), (2, 'O''Brien'), (3, 'Đà Nẵng');
                """);
        try (Connection connection = jdbc("postgres"); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS shop_restore WITH (FORCE)");
            statement.execute("CREATE DATABASE shop_restore");
        }
    }

    @Test
    void testsConnectionDumpsAndRestoresIntoAnotherPostgresqlDatabase() throws Exception {
        assertThat(connectionTest.test(target("shop_source")).successful()).isTrue();
        Path artifact = artifacts.resolve("shop.dump");

        long size = backup.dumpTo(target("shop_source"), artifact);
        assertThat(size).isPositive();
        assertThat(artifact).isNotEmptyFile();

        execute("shop_restore", "CREATE TABLE widgets (id integer PRIMARY KEY, name text); INSERT INTO widgets VALUES (99, 'later');");
        restore.restore(target("shop_restore"), artifact);

        assertThat(query("shop_restore", "SELECT count(*) FROM widgets")).isEqualTo("3");
        assertThat(query("shop_restore", "SELECT string_agg(name, ',' ORDER BY id) FROM widgets"))
                .isEqualTo("alpha,O'Brien,Đà Nẵng");
        assertThat(query("shop_source", "SELECT count(*) FROM widgets")).isEqualTo("3");
    }

    @Test
    void verifiesARealBackupInADisposablePostgresqlContainerAndRemovesIt() {
        Path artifact = artifacts.resolve("shop.dump");
        backup.dumpTo(target("shop_source"), artifact);
        UUID verificationId = UUID.randomUUID();
        DockerVerificationSupport docker = dockerSupport();

        var result = new PostgresRestoreVerificationAdapter(
                docker, true, "postgres:17-alpine", Duration.ofMinutes(2))
                .verify(verificationId, sourceTarget(), artifact);

        assertThat(result.checkedObjects()).isEqualTo(1);
        assertThat(docker.success(List.of("docker", "inspect",
                DockerVerificationSupport.containerName(DatabaseEngine.POSTGRESQL, verificationId)),
                Duration.ofSeconds(10))).isFalse();

        UUID corruptId = UUID.randomUUID();
        Path corrupt = artifacts.resolve("corrupt-verification.dump");
        try {
            java.nio.file.Files.writeString(corrupt, "not a pg_dump archive");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThatThrownBy(() -> new PostgresRestoreVerificationAdapter(
                docker, true, "postgres:17-alpine", Duration.ofMinutes(2))
                .verify(corruptId, sourceTarget(), corrupt))
                .hasMessageContaining("pg_restore failed");
        assertThat(docker.success(List.of("docker", "inspect",
                DockerVerificationSupport.containerName(DatabaseEngine.POSTGRESQL, corruptId)),
                Duration.ofSeconds(10))).isFalse();
    }

    private static DatabaseConnection target(String database) {
        return new DatabaseConnection(
                DatabaseEngine.POSTGRESQL,
                POSTGRES.getHost(),
                POSTGRES.getFirstMappedPort(),
                database,
                POSTGRES.getUsername(),
                POSTGRES.getPassword());
    }

    private static DatabaseTarget sourceTarget() {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name("source").engine(DatabaseEngine.POSTGRESQL)
                .host("unused").port(5432).databaseName("shop_source").username("unused")
                .passwordCiphertext("unused").createdAt(Instant.EPOCH).build();
    }

    private static DockerVerificationSupport dockerSupport() {
        return new DockerVerificationSupport(new ProcessRunner(), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    private static String clientMajor() {
        try {
            Process process = new ProcessBuilder(PG_DUMP.toString(), "--version")
                    .redirectErrorStream(true)
                    .start();
            String version = new String(process.getInputStream().readAllBytes()).strip();
            if (process.waitFor() != 0) {
                throw new IllegalStateException("Could not read pg_dump version: " + version);
            }
            Matcher matcher = CLIENT_MAJOR.matcher(version);
            if (!matcher.find()) {
                throw new IllegalStateException("Unrecognised pg_dump version: " + version);
            }
            return matcher.group(1);
        } catch (Exception e) {
            throw new IllegalStateException("The pg_dump client version could not be determined", e);
        }
    }

    private static Connection jdbc(String database) throws Exception {
        return DriverManager.getConnection(
                "jdbc:postgresql://%s:%d/%s".formatted(
                        POSTGRES.getHost(), POSTGRES.getFirstMappedPort(), database),
                POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String database, String sql) throws Exception {
        try (Connection connection = jdbc(database); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String query(String database, String sql) throws Exception {
        try (Connection connection = jdbc(database);
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
