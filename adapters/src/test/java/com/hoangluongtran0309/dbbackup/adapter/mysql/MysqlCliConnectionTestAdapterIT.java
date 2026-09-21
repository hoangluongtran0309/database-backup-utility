package com.hoangluongtran0309.dbbackup.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

/**
 * Drives the real {@code mysql} client against a real server.
 *
 * <p>The client runs on the host, not inside the container, because that is how
 * the application runs it. Nothing here is conditional on the binary being
 * present: if {@code mysql} is missing this class fails, which is the point —
 * a test that quietly skips itself reports success for work it never did.
 */
@Testcontainers
class MysqlCliConnectionTestAdapterIT {

    private static final Path MYSQL_BINARY = configuredPath("MYSQL_CLIENT_PATH", "/usr/bin/mysql");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop")
            .withUsername("backup")
            .withPassword("s3cr3t");

    private ConnectionTestPort adapter;

    @BeforeAll
    static void requireTheClientBinary() {
        assertThat(MYSQL_BINARY)
                .as("the mysql client must be installed; this suite must fail, not skip, without it")
                .isExecutable();
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        adapter = new MysqlCliConnectionTestAdapter(
                new MysqlClient(new ProcessRunner(), MYSQL_BINARY, Duration.ofSeconds(10)));
    }

    @Test
    void reportsSuccessForValidCredentials() {
        ConnectionTestPort.Result result = adapter.test(connection("backup", "s3cr3t", "shop"));

        assertThat(result.successful()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void reportsMysqlsOwnWordsForABadPassword() {
        ConnectionTestPort.Result result = adapter.test(connection("backup", "wrong", "shop"));

        assertThat(result.successful()).isFalse();
        // The verbatim message is the whole point: this sends an operator to
        // the credentials, not to the network.
        assertThat(result.message()).contains("Access denied for user");
    }

    /**
     * Note what MySQL actually says here. A least-privileged backup user gets
     * "Access denied ... to database" rather than "Unknown database", because
     * the server will not reveal whether the schema exists. Passing the message
     * through verbatim is what lets an operator see that distinction; a
     * house-written "Unknown database" would have been wrong.
     */
    @Test
    void reportsMysqlsOwnWordsForASchemaTheUserCannotReach() {
        ConnectionTestPort.Result result = adapter.test(connection("backup", "s3cr3t", "no_such_schema"));

        assertThat(result.successful()).isFalse();
        assertThat(result.message())
                .contains("no_such_schema")
                .containsAnyOf("Access denied", "Unknown database");
    }

    @Test
    void reportsAFailureRatherThanThrowingForAnUnreachablePort() {
        ConnectionTestPort.Result result = adapter.test(
                new DatabaseConnection(DatabaseEngine.MYSQL, MYSQL.getHost(), 1, "shop", "backup", "s3cr3t"));

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void reportsAFailureRatherThanThrowingWhenTheBinaryIsMissing() throws Exception {
        Path missing = Files.createTempDirectory("mysql-client").resolve("mysql");
        Files.writeString(missing, "#!/bin/sh\nexit 0\n");
        missing.toFile().setExecutable(true);

        ConnectionTestPort adapterWithBrokenBinary = new MysqlCliConnectionTestAdapter(
                new MysqlClient(new ProcessRunner(), missing, Duration.ofSeconds(5)));
        Files.delete(missing);

        ConnectionTestPort.Result result =
                adapterWithBrokenBinary.test(connection("backup", "s3cr3t", "shop"));

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("Could not start");
    }

    private static DatabaseConnection connection(String user, String password, String database) {
        return new DatabaseConnection(DatabaseEngine.MYSQL,
                MYSQL.getHost(), MYSQL.getFirstMappedPort(), database, user, password);
    }

    private static Path configuredPath(String environment, String fallback) {
        String configured = System.getenv(environment);
        return Path.of(configured == null || configured.isBlank() ? fallback : configured);
    }
}
