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
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalRestorePort;

/**
 * The test this project exists for: back up a real schema, destroy it, restore
 * it, and prove the data came back.
 *
 * <p>Everything else asserts that a step behaved; this asserts that the whole
 * point works.
 */
@Testcontainers
class BackupRestoreRoundTripIT {

    private static final Path MYSQL = Path.of("/usr/bin/mysql");
    private static final Path MYSQLDUMP = Path.of("/usr/bin/mysqldump");

    @org.testcontainers.junit.jupiter.Container
    static final MySQLContainer SERVER = new MySQLContainer("mysql:8.4")
            .withDatabaseName("shop")
            .withUsername("backup")
            .withPassword("s3cr3t");

    @TempDir
    Path artifacts;

    private MysqlLogicalBackupPort backup;
    private MysqlLogicalRestorePort restore;

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

        restore.restore(new MysqlConnection(
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

    /** The decompressed copy is large; it must not be left behind. */
    @Test
    void removesItsTemporaryFileWhateverHappens() throws Exception {
        Path artifact = artifacts.resolve("shop.sql.gz");
        backup.dumpTo(connection("s3cr3t"), artifact);

        restore.restore(connection("s3cr3t"), artifact);
        assertThat(temporaryFiles()).isEmpty();

        assertThatThrownBy(() -> restore.restore(connection("wrong"), artifact))
                .isInstanceOf(RestoreFailedException.class);
        assertThat(temporaryFiles()).isEmpty();
    }

    private List<String> temporaryFiles() throws Exception {
        try (var files = Files.list(artifacts)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.contains(".restore.sql"))
                    .toList();
        }
    }

    private static MysqlConnection connection(String password) {
        return new MysqlConnection(
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
}
