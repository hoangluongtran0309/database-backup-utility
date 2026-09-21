package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mariadb.MariaDBContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Runs MariaDB's own client pair against a real MariaDB server end to end. */
@Testcontainers
class MariaDbBackupRestoreIT {

    private static final Path MARIADB = configuredPath("MARIADB_CLIENT_PATH", "/usr/bin/mariadb");
    private static final Path MARIADB_DUMP = configuredPath("MARIADB_DUMP_PATH", "/usr/bin/mariadb-dump");

    @org.testcontainers.junit.jupiter.Container
    static final MariaDBContainer SERVER = new MariaDBContainer("mariadb:10.11")
            .withDatabaseName("shop")
            .withUsername("backup")
            .withPassword("s3cr3t");

    @TempDir
    Path artifacts;

    private MariaDbCliConnectionTestAdapter connectionTest;
    private MariaDbDumpBackupAdapter backup;
    private MariaDbRestoreAdapter restore;

    @BeforeAll
    static void requireTheBinaries() {
        assertThat(MARIADB).as("the mariadb client must be installed").isExecutable();
        assertThat(MARIADB_DUMP).as("mariadb-dump must be installed").isExecutable();
    }

    @BeforeEach
    void setUp() {
        ProcessRunner runner = new ProcessRunner();
        connectionTest = new MariaDbCliConnectionTestAdapter(
                new MariaDbClient(runner, MARIADB, Duration.ofSeconds(10)));
        backup = new MariaDbDumpBackupAdapter(runner, MARIADB_DUMP, Duration.ofMinutes(2));
        restore = new MariaDbRestoreAdapter(
                runner, MARIADB, Duration.ofSeconds(10), Duration.ofMinutes(2));

        rootSql("""
                DROP DATABASE IF EXISTS shop;
                DROP DATABASE IF EXISTS shop_drill;
                CREATE DATABASE shop;
                GRANT ALL PRIVILEGES ON shop.* TO 'backup'@'%';
                GRANT SELECT ON mysql.proc TO 'backup'@'%';
                """);
        sql("""
                CREATE TABLE orders (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    customer VARCHAR(64) NOT NULL,
                    total DECIMAL(10,2) NOT NULL,
                    note TEXT
                );
                CREATE TABLE items (
                    id INT PRIMARY KEY AUTO_INCREMENT,
                    order_id INT,
                    sku VARCHAR(32),
                    payload LONGTEXT
                );
                CREATE VIEW recent_orders AS SELECT * FROM orders WHERE id > 1;
                CREATE PROCEDURE order_count() SELECT COUNT(*) AS total FROM orders;
                CREATE TRIGGER uppercase_sku BEFORE INSERT ON items
                    FOR EACH ROW SET NEW.sku = UPPER(NEW.sku);
                CREATE EVENT purge_impossible_orders ON SCHEDULE EVERY 1 DAY
                    DO DELETE FROM orders WHERE id < 0;
                INSERT INTO orders (customer, total, note) VALUES
                    ('Ada Lovelace', 99.50, 'first'),
                    ("O'Brien", 12.00, 'quote in the name'),
                    ('Ünicode FIne', 7.25, 'accents and a ligature');
                INSERT INTO items (order_id, sku) VALUES
                    (1,'sku-1'), (1,'sku-2'), (2,'sku-3');
                """);
    }

    @Test
    void connectionProbeUsesMariaDbsOwnResultAndErrors() {
        assertThat(connectionTest.test(connection("s3cr3t", "shop")).successful()).isTrue();

        var badPassword = connectionTest.test(connection("wrong", "shop"));
        assertThat(badPassword.successful()).isFalse();
        assertThat(badPassword.message()).contains("Access denied");

        var badDatabase = connectionTest.test(connection("s3cr3t", "no_such_database"));
        assertThat(badDatabase.successful()).isFalse();
        assertThat(badDatabase.message()).containsAnyOf("Access denied", "Unknown database");
    }

    @Test
    void artifactContainsDataAndEveryLogicalDatabaseObject() throws Exception {
        Path artifact = artifacts.resolve("shop.sql.gz");

        long size = backup.dumpTo(connection("s3cr3t", "shop"), artifact);
        String dump = gunzip(artifact);

        assertThat(size).isEqualTo(Files.size(artifact)).isPositive();
        assertThat(dump)
                .contains("CREATE TABLE `orders`")
                .contains("Ada Lovelace")
                .contains("O\\'Brien")
                .contains("recent_orders")
                .contains("order_count")
                .contains("uppercase_sku")
                .contains("purge_impossible_orders")
                .doesNotContain("CREATE DATABASE")
                .doesNotContain("USE `shop`");
    }

    @Test
    void restoresEveryRowAndObjectAfterTheDatabaseIsEmptied() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t", "shop"), artifact);

        sql("DROP VIEW recent_orders; DROP TABLE items; DROP TABLE orders;");
        restore.restore(connection("s3cr3t", "shop"), "shop", artifact);

        assertThat(tables("shop")).containsExactlyInAnyOrder("items", "orders");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM orders")).isEqualTo("3");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM items")).isEqualTo("3");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM recent_orders")).isEqualTo("2");
        assertThat(queryIn("shop", "CALL order_count()")).isEqualTo("3");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA='shop' AND TRIGGER_NAME='uppercase_sku'"))
                .isEqualTo("1");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM information_schema.EVENTS "
                + "WHERE EVENT_SCHEMA='shop' AND EVENT_NAME='purge_impossible_orders'"))
                .isEqualTo("1");
    }

    @Test
    void restoresExactValuesIntoAnotherDatabaseWithoutChangingTheSource() {
        rootSql("""
                CREATE DATABASE shop_drill;
                GRANT ALL PRIVILEGES ON shop_drill.* TO 'backup'@'%';
                """);
        Path artifact = artifacts.resolve("shop.sql.gz");
        String before = queryIn("shop",
                "SELECT GROUP_CONCAT(CONCAT(id,':',customer,':',total,':',note) ORDER BY id) FROM orders");
        backup.dumpTo(connection("s3cr3t", "shop"), artifact);
        sql("INSERT INTO orders (customer, total) VALUES ('Only in source', 1.00)");

        restore.restore(connection("s3cr3t", "shop_drill"), "shop", artifact);

        assertThat(queryIn("shop_drill",
                "SELECT GROUP_CONCAT(CONCAT(id,':',customer,':',total,':',note) ORDER BY id) FROM orders"))
                .isEqualTo(before)
                .contains("O'Brien")
                .contains("Ünicode");
        assertThat(queryIn("shop_drill", "SELECT COUNT(*) FROM recent_orders")).isEqualTo("2");
        assertThat(queryIn("shop_drill", "CALL order_count()")).isEqualTo("3");
        assertThat(queryIn("shop_drill", "SELECT COUNT(*) FROM information_schema.TRIGGERS "
                + "WHERE TRIGGER_SCHEMA='shop_drill' AND TRIGGER_NAME='uppercase_sku'"))
                .isEqualTo("1");
        assertThat(queryIn("shop_drill", "SELECT COUNT(*) FROM information_schema.EVENTS "
                + "WHERE EVENT_SCHEMA='shop_drill' AND EVENT_NAME='purge_impossible_orders'"))
                .isEqualTo("1");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM orders")).isEqualTo("4");
    }

    @Test
    void repeatedRestoreReplacesKnownTablesAndKeepsUnrelatedTables() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t", "shop"), artifact);
        sql("CREATE TABLE audit (id INT PRIMARY KEY); INSERT INTO audit VALUES (1)");

        restore.restore(connection("s3cr3t", "shop"), "shop", artifact);
        restore.restore(connection("s3cr3t", "shop"), "shop", artifact);

        assertThat(queryIn("shop", "SELECT COUNT(*) FROM orders")).isEqualTo("3");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM audit")).isEqualTo("1");
    }

    @Test
    void streamsADumpLargerThanAPipeWithoutWritingADecompressedCopy() throws Exception {
        sql("INSERT INTO items (order_id, sku, payload) VALUES (9, 'large', REPEAT('x', 200000))");
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t", "shop"), artifact);
        sql("DROP TABLE items");

        restore.restore(connection("s3cr3t", "shop"), "shop", artifact);

        assertThat(queryIn("shop", "SELECT LENGTH(payload) FROM items WHERE sku='LARGE'"))
                .isEqualTo("200000");
        assertThat(filesBesideArtifact()).containsExactly("shop.sql.gz");
    }

    @Test
    void refusesMissingInvalidAndTruncatedArtifactsBeforeTouchingTheTarget() throws Exception {
        String originalCount = queryIn("shop", "SELECT COUNT(*) FROM orders");
        Path missing = artifacts.resolve("missing.sql.gz");
        assertThatThrownBy(() -> restore.restore(connection("s3cr3t", "shop"), "shop", missing))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("missing or unreadable");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM orders")).isEqualTo(originalCount);

        Path invalid = Files.writeString(artifacts.resolve("invalid.sql.gz"), "not gzip");
        assertThatThrownBy(() -> restore.restore(connection("s3cr3t", "shop"), "shop", invalid))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Nothing was restored");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM orders")).isEqualTo(originalCount);

        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t", "shop"), artifact);
        byte[] whole = Files.readAllBytes(artifact);
        Files.write(artifact, java.util.Arrays.copyOf(whole, whole.length / 2));
        sql("INSERT INTO orders (customer, total) VALUES ('Must survive', 1.00)");

        assertThatThrownBy(() -> restore.restore(connection("s3cr3t", "shop"), "shop", artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Nothing was restored");
        assertThat(queryIn("shop", "SELECT COUNT(*) FROM orders")).isEqualTo("4");
    }

    @Test
    void reportsMariaDbsOwnWordsForBadRestoreCredentials() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t", "shop"), artifact);

        assertThatThrownBy(() -> restore.restore(connection("wrong", "shop"), "shop", artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Access denied");
    }

    private List<String> filesBesideArtifact() throws Exception {
        try (var files = Files.list(artifacts)) {
            return files.map(path -> path.getFileName().toString()).toList();
        }
    }

    private static String gunzip(Path archive) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static DatabaseConnection connection(String password, String database) {
        return new DatabaseConnection(DatabaseEngine.MARIADB,
                SERVER.getHost(), SERVER.getFirstMappedPort(), database, "backup", password);
    }

    private static List<String> tables(String database) {
        String out = queryIn(database, "SELECT GROUP_CONCAT(table_name ORDER BY table_name) "
                + "FROM information_schema.tables WHERE table_schema='" + database + "' AND table_type='BASE TABLE'");
        return out.isBlank() || "NULL".equals(out) ? List.of() : List.of(out.split(","));
    }

    private static String queryIn(String database, String sql) {
        return exec("mariadb", "-uroot", "-p" + SERVER.getPassword(), database,
                "-N", "--batch", "-e", sql).strip();
    }

    private static void sql(String script) {
        exec("mariadb", "-u" + SERVER.getUsername(), "-p" + SERVER.getPassword(),
                "shop", "-e", script);
    }

    private static void rootSql(String script) {
        exec("mariadb", "-uroot", "-p" + SERVER.getPassword(), "-e", script);
    }

    private static String exec(String... command) {
        try {
            Container.ExecResult result = SERVER.execInContainer(command);
            assertThat(result.getExitCode()).as(result.getStderr()).isZero();
            return result.getStdout();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path configuredPath(String environment, String fallback) {
        String configured = System.getenv(environment);
        return Path.of(configured == null || configured.isBlank() ? fallback : configured);
    }
}
