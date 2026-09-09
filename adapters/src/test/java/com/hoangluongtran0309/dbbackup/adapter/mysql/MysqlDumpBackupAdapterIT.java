package com.hoangluongtran0309.dbbackup.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalBackupPort;

/**
 * Runs the real {@code mysqldump} against a real server and reads the file it
 * produced. Fails, never skips, if the binary is absent.
 */
@Testcontainers
class MysqlDumpBackupAdapterIT {

    private static final Path MYSQLDUMP = Path.of("/usr/bin/mysqldump");

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop")
            .withUsername("backup")
            .withPassword("s3cr3t");

    @TempDir
    Path outputDir;

    private MysqlLogicalBackupPort adapter;

    @BeforeAll
    static void seed() throws Exception {
        assertThat(MYSQLDUMP)
                .as("mysqldump must be installed; this suite must fail, not skip, without it")
                .isExecutable();

        // Seeded from inside the container, so no JDBC driver is needed here.
        execute("""
                CREATE TABLE orders (id INT PRIMARY KEY, customer VARCHAR(64) NOT NULL);
                INSERT INTO orders VALUES (1, 'Ada Lovelace'), (2, "O'Brien");
                CREATE VIEW recent_orders AS SELECT * FROM orders WHERE id > 1;
                """);
    }

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        adapter = new MysqlDumpBackupAdapter(new ProcessRunner(), MYSQLDUMP, Duration.ofMinutes(2));
    }

    @Test
    void writesADumpContainingTheSchemaAndTheRows() throws Exception {
        Path destination = outputDir.resolve("shop.sql");

        long sizeBytes = adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        assertThat(destination).exists();
        assertThat(sizeBytes).isEqualTo(Files.size(destination)).isPositive();

        String dump = Files.readString(destination);
        assertThat(dump)
                .contains("CREATE TABLE `orders`")
                .contains("Ada Lovelace")
                .contains("O''Brien".replace("''", "\\'"))
                .contains("recent_orders");
    }

    /**
     * The dump must be restorable into a schema with a different name, so it
     * must not hardcode the source name. This is what passing the database
     * positionally, rather than with --databases, buys.
     */
    @Test
    void theDumpDoesNotHardcodeTheSourceSchemaName() throws Exception {
        Path destination = outputDir.resolve("shop.sql");

        adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        assertThat(Files.readString(destination))
                .doesNotContain("CREATE DATABASE")
                .doesNotContain("USE `shop`");
    }

    /** Restoring as a non-SUPER user fails if this leaks in. */
    @Test
    void theDumpDoesNotSetGtidPurged() throws Exception {
        Path destination = outputDir.resolve("shop.sql");

        adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        assertThat(Files.readString(destination)).doesNotContain("GTID_PURGED");
    }

    /**
     * A truncated dump is worse than no dump: it looks like a backup and
     * restores as silent data loss.
     */
    @Test
    void leavesNoPartialFileBehindWhenTheDumpFails() {
        Path destination = outputDir.resolve("shop.sql");

        assertThatThrownBy(() -> adapter.dumpTo(connection("backup", "wrong-password"), destination))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("Access denied");

        assertThat(destination).doesNotExist();
    }

    @Test
    void reportsMysqldumpsOwnWordsForAnUnreachableServer() {
        assertThatThrownBy(() -> adapter.dumpTo(
                new MysqlConnection(MYSQL.getHost(), 1, "shop", "backup", "s3cr3t"),
                outputDir.resolve("shop.sql")))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("mysqldump exited with");
    }

    @Test
    void neverPutsThePasswordOnTheCommandLine() {
        var command = new MysqlDumpBackupAdapter(new ProcessRunner(), MYSQLDUMP, Duration.ofMinutes(2))
                .command(connection("backup", "s3cr3t"), outputDir.resolve("shop.sql"));

        assertThat(command).noneMatch(argument -> argument.contains("s3cr3t"));
    }

    private static MysqlConnection connection(String user, String password) {
        return new MysqlConnection(
                MYSQL.getHost(), MYSQL.getFirstMappedPort(), "shop", user, password);
    }

    private static void execute(String sql) throws Exception {
        var result = MYSQL.execInContainer(
                "mysql", "-uroot", "-p" + MYSQL.getPassword(), "shop", "-e", sql);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
    }
}
