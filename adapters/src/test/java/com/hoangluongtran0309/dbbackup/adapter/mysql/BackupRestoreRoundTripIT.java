package com.hoangluongtran0309.dbbackup.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/**
 * The test this project exists for: back up a real schema, destroy it, restore
 * it, and prove the data came back.
 *
 * <p>Everything else asserts that a step behaved; this asserts that the whole
 * point works.
 */
@Testcontainers
class BackupRestoreRoundTripIT {

    private static final Path MYSQL = configuredPath("MYSQL_CLIENT_PATH", "/usr/bin/mysql");
    private static final Path MYSQLDUMP = configuredPath("MYSQLDUMP_PATH", "/usr/bin/mysqldump");

    @org.testcontainers.junit.jupiter.Container
    static final MySQLContainer SERVER = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop")
            .withUsername("backup")
            .withPassword("s3cr3t");

    @TempDir
    Path artifacts;

    private LogicalBackupPort backup;
    private LogicalRestorePort restore;

    @BeforeAll
    static void requireTheBinaries() {
        assertThat(MYSQL).as("the mysql client must be installed").isExecutable();
        assertThat(MYSQLDUMP).as("mysqldump must be installed").isExecutable();
    }

    @BeforeEach
    void setUp() {
        backup = new MysqlDumpBackupAdapter(new ProcessRunner(), MYSQLDUMP, Duration.ofMinutes(2));
        restore = new MysqlRestoreAdapter(
                new ProcessRunner(), MYSQL, Duration.ofSeconds(10), Duration.ofMinutes(2));

        // Recreated wholesale, not table by table: one of the tests below
        // deliberately leaves an extra table behind, and dropping a fixed list
        // would let it leak into the next test.
        rootSql("DROP DATABASE IF EXISTS shop; CREATE DATABASE shop;");

        sql("""
            CREATE TABLE orders (
                id INT PRIMARY KEY AUTO_INCREMENT,
                customer VARCHAR(64) NOT NULL,
                total DECIMAL(10,2) NOT NULL,
                note TEXT
            );
            CREATE TABLE items (id INT PRIMARY KEY AUTO_INCREMENT, order_id INT, sku VARCHAR(32));
            INSERT INTO orders (customer, total, note) VALUES
                ('Ada Lovelace', 99.50, 'first'),
                ("O'Brien", 12.00, 'quote in the name'),
                ('Ünicode FIne', 7.25, 'accents and a ligature');
            INSERT INTO items (order_id, sku) VALUES (1,'SKU-1'), (1,'SKU-2'), (2,'SKU-3');
            """);
    }

    @Test
    void restoresEveryRowAfterTheSchemaIsDestroyed() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        sql("DROP TABLE items; DROP TABLE orders;");
        assertThat(tables()).isEmpty();

        restore.restore(connection("s3cr3t"), artifact);

        assertThat(tables()).containsExactlyInAnyOrder("items", "orders");
        assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo("3");
        assertThat(query("SELECT COUNT(*) FROM items")).isEqualTo("3");
    }

    /** Values, not just row counts — including the ones that survive quoting badly. */
    @Test
    void bringsBackTheExactValuesIncludingQuotesAndNonAsciiText() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        String before = query("SELECT GROUP_CONCAT(CONCAT(id,':',customer,':',total,':',note) ORDER BY id) FROM orders");

        backup.dumpTo(connection("s3cr3t"), artifact);
        sql("DROP TABLE items; DROP TABLE orders;");
        restore.restore(connection("s3cr3t"), artifact);

        assertThat(query("SELECT GROUP_CONCAT(CONCAT(id,':',customer,':',total,':',note) ORDER BY id) FROM orders"))
                .isEqualTo(before)
                .contains("O'Brien")
                .contains("Ünicode");
    }

    @Test
    void restoringOverALiveSchemaReplacesTheRowsItCarries() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        sql("INSERT INTO orders (customer, total) VALUES ('Added later', 1.00);");
        assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo("4");

        restore.restore(connection("s3cr3t"), artifact);

        // The dump recreates the table, so the later row is gone with it.
        assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo("3");
    }

    /**
     * The caveat the confirmation page states: this applies a dump, it does not
     * reset the schema. A table the backup never knew about survives.
     */
    @Test
    void leavesTablesTheBackupDoesNotContainUntouched() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        sql("CREATE TABLE audit (id INT PRIMARY KEY); INSERT INTO audit VALUES (1);");

        restore.restore(connection("s3cr3t"), artifact);

        assertThat(tables()).contains("audit");
        assertThat(query("SELECT COUNT(*) FROM audit")).isEqualTo("1");
    }

    /**
     * What ADR-005 kept the dump free of {@code CREATE DATABASE} and {@code USE}
     * for, and ADR-014 relies on: a backup of {@code shop} loads into a schema
     * with another name, and {@code shop} itself is not touched.
     */
    @Test
    void restoresIntoASchemaWithAnotherNameLeavingTheSourceAlone() {
        rootSql("""
            DROP DATABASE IF EXISTS shop_drill; CREATE DATABASE shop_drill;
            GRANT ALL ON shop_drill.* TO 'backup'@'%';
            """);
        Path artifact = artifacts.resolve("shop.sql.gz");
        String before = query("SELECT GROUP_CONCAT(CONCAT(id,':',customer,':',total) ORDER BY id) FROM orders");
        backup.dumpTo(connection("s3cr3t"), artifact);
        sql("INSERT INTO orders (customer, total) VALUES ('Only in the source', 1.00);");

        restore.restore(new DatabaseConnection(DatabaseEngine.MYSQL,
                SERVER.getHost(), SERVER.getFirstMappedPort(), "shop_drill", "backup", "s3cr3t"), artifact);

        assertThat(queryIn("shop_drill",
                "SELECT GROUP_CONCAT(CONCAT(id,':',customer,':',total) ORDER BY id) FROM orders"))
                .isEqualTo(before);
        assertThat(queryIn("shop_drill", "SELECT COUNT(*) FROM items")).isEqualTo("3");
        // The source keeps the row added after the backup: nothing went there.
        assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo("4");
    }

    @Test
    void aBackupCanBeRestoredMoreThanOnce() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        restore.restore(connection("s3cr3t"), artifact);
        restore.restore(connection("s3cr3t"), artifact);

        assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo("3");
    }

    @Test
    void reportsTheClientsOwnWordsForBadCredentials() {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        assertThatThrownBy(() -> restore.restore(connection("wrong"), artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    void refusesAnArtifactThatIsNotThere() {
        assertThatThrownBy(() -> restore.restore(connection("s3cr3t"), artifacts.resolve("gone.sql.gz")))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("missing or unreadable");
    }

    @Test
    void refusesAnArtifactThatIsNotAGzipArchive() throws Exception {
        Path corrupt = Files.writeString(artifacts.resolve("corrupt.sql.gz"), "this is not gzip");

        assertThatThrownBy(() -> restore.restore(connection("s3cr3t"), corrupt))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Could not read the backup artifact");
    }

    /**
     * A dump far larger than a pipe buffer goes through the client's stdin
     * whole: the rows are all there, not the first 64 KiB of them.
     */
    @Test
    void restoresADumpFarLargerThanAPipe() {
        sql("""
            SET SESSION cte_max_recursion_depth = 50000;
            INSERT INTO items (order_id, sku)
                WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 40000)
                SELECT n, CONCAT('SKU-', n) FROM seq;
            """);
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);
        sql("DROP TABLE items;");

        restore.restore(connection("s3cr3t"), artifact);

        assertThat(query("SELECT COUNT(*) FROM items")).isEqualTo("40003");
        assertThat(query("SELECT sku FROM items ORDER BY id DESC LIMIT 1"))
                .isEqualTo("SKU-40000");
    }

    /**
     * What the temporary file used to guarantee, and the archive check now
     * does: an archive that stops halfway is found out before the client
     * starts, so not one statement of it reaches the target.
     */
    @Test
    void refusesATruncatedArchiveWithoutTouchingTheTarget() throws Exception {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);
        byte[] whole = Files.readAllBytes(artifact);
        Files.write(artifact, java.util.Arrays.copyOf(whole, whole.length / 2));
        sql("INSERT INTO orders (customer, total) VALUES ('Added later', 1.00);");

        assertThatThrownBy(() -> restore.restore(connection("s3cr3t"), artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Could not read the backup artifact")
                .hasMessageContaining("Nothing was restored");

        // The dump would have recreated the table without this row.
        assertThat(query("SELECT COUNT(*) FROM orders")).isEqualTo("4");
    }

    /** No decompressed copy is written anywhere beside the archive, whatever the outcome. */
    @Test
    void writesNothingBesideTheArtifact() throws Exception {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        restore.restore(connection("s3cr3t"), artifact);
        assertThat(filesBesideTheArtifact()).containsExactly("shop.sql.gz");

        assertThatThrownBy(() -> restore.restore(connection("wrong"), artifact))
                .isInstanceOf(RestoreFailedException.class);
        assertThat(filesBesideTheArtifact()).containsExactly("shop.sql.gz");
    }

    private List<String> filesBesideTheArtifact() throws Exception {
        try (var files = Files.list(artifacts)) {
            return files.map(p -> p.getFileName().toString()).toList();
        }
    }

    private static DatabaseConnection connection(String password) {
        return new DatabaseConnection(DatabaseEngine.MYSQL,
                SERVER.getHost(), SERVER.getFirstMappedPort(), "shop", "backup", password);
    }

    private static List<String> tables() {
        String out = query("SELECT GROUP_CONCAT(table_name ORDER BY table_name) "
                + "FROM information_schema.tables WHERE table_schema='shop'");
        return out.isBlank() || "NULL".equals(out) ? List.of() : List.of(out.split(","));
    }

    private static String query(String sql) {
        return queryIn("shop", sql);
    }

    private static String queryIn(String schema, String sql) {
        return exec("mysql", "-uroot", "-p" + SERVER.getPassword(), schema,
                "-N", "--batch", "-e", sql).strip();
    }

    private static void sql(String script) {
        exec("mysql", "-uroot", "-p" + SERVER.getPassword(), "shop", "-e", script);
    }

    /** Without a default schema, for statements that act on the database itself. */
    private static void rootSql(String script) {
        exec("mysql", "-uroot", "-p" + SERVER.getPassword(), "-e", script);
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
