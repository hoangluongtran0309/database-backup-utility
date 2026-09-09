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
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.port.MysqlConnectionTestPort;

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

    private static final Path MYSQL_BINARY = Path.of("/usr/bin/mysql");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop")
            .withUsername("backup")
            .withPassword("s3cr3t");

    private MysqlConnectionTestPort adapter;

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
        MysqlConnectionTestPort.Result result = adapter.test(connection("backup", "s3cr3t", "shop"));

        assertThat(result.successful()).isTrue();
        assertThat(result.message()).isEmpty();
    }

    @Test
    void reportsMysqlsOwnWordsForABadPassword() {
        MysqlConnectionTestPort.Result result = adapter.test(connection("backup", "wrong", "shop"));

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
        MysqlConnectionTestPort.Result result = adapter.test(connection("backup", "s3cr3t", "no_such_schema"));

        assertThat(result.successful()).isFalse();
        assertThat(result.message())
                .contains("no_such_schema")
                .containsAnyOf("Access denied", "Unknown database");
    }

    @Test
    void reportsAFailureRatherThanThrowingForAnUnreachablePort() {
        MysqlConnectionTestPort.Result result = adapter.test(
                new MysqlConnection(MYSQL.getHost(), 1, "shop", "backup", "s3cr3t"));

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void reportsAFailureRatherThanThrowingWhenTheBinaryIsMissing() throws Exception {
        Path missing = Files.createTempDirectory("mysql-client").resolve("mysql");
        Files.writeString(missing, "#!/bin/sh\nexit 0\n");
        missing.toFile().setExecutable(true);

        MysqlConnectionTestPort adapterWithBrokenBinary = new MysqlCliConnectionTestAdapter(
                new MysqlClient(new ProcessRunner(), missing, Duration.ofSeconds(5)));
        Files.delete(missing);

        MysqlConnectionTestPort.Result result =
                adapterWithBrokenBinary.test(connection("backup", "s3cr3t", "shop"));

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("Could not start");
    }

    private static MysqlConnection connection(String user, String password, String database) {
        return new MysqlConnection(
                MYSQL.getHost(), MYSQL.getFirstMappedPort(), database, user, password);
    }
}
