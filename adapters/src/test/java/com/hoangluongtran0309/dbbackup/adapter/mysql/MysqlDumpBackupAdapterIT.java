package com.hoangluongtran0309.dbbackup.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.GZIPInputStream;

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
        Path destination = outputDir.resolve("shop.sql.gz");

        long sizeBytes = adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        assertThat(destination).exists();
        assertThat(sizeBytes).isEqualTo(Files.size(destination)).isPositive();

        String dump = gunzip(destination);
        assertThat(dump)
                .contains("CREATE TABLE `orders`")
                .contains("Ada Lovelace")
                .contains("O\\'Brien")
                .contains("recent_orders");
    }

    /**
     * The gzip trailer is only written when the stream closes, so a dump that
     * is merely "written" is not necessarily readable. Decompressing the whole
     * file is the only assertion that proves it.
     */
    @Test
    void theArtifactIsAValidGzipArchive() throws Exception {
        Path destination = outputDir.resolve("shop.sql.gz");

        adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        byte[] header = new byte[2];
        try (InputStream in = Files.newInputStream(destination)) {
            assertThat(in.read(header)).isEqualTo(2);
        }
        // 0x1f 0x8b — the gzip magic number.
        assertThat(header).containsExactly((byte) 0x1f, (byte) 0x8b);
        assertThat(gunzip(destination)).startsWith("-- MySQL dump");
    }

    /** Compression has to be worth doing; SQL text is extremely compressible. */
    @Test
    void theArchiveIsSubstantiallySmallerThanTheDumpItHolds() throws Exception {
        Path destination = outputDir.resolve("shop.sql.gz");

        long compressed = adapter.dumpTo(connection("backup", "s3cr3t"), destination);
        long uncompressed = gunzip(destination).getBytes(StandardCharsets.UTF_8).length;

        assertThat(compressed).isLessThan(uncompressed);
    }

    /**
     * The dump must be restorable into a schema with a different name, so it
     * must not hardcode the source name. This is what passing the database
     * positionally, rather than with --databases, buys.
     */
    @Test
    void theDumpDoesNotHardcodeTheSourceSchemaName() throws Exception {
        Path destination = outputDir.resolve("shop.sql.gz");

        adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        assertThat(gunzip(destination))
                .doesNotContain("CREATE DATABASE")
                .doesNotContain("USE `shop`");
    }

    /** Restoring as a non-SUPER user fails if this leaks in. */
    @Test
    void theDumpDoesNotSetGtidPurged() throws Exception {
        Path destination = outputDir.resolve("shop.sql.gz");

        adapter.dumpTo(connection("backup", "s3cr3t"), destination);

        assertThat(gunzip(destination)).doesNotContain("GTID_PURGED");
    }

    /**
     * A truncated dump is worse than no dump: it looks like a backup and
     * restores as silent data loss.
     */
    @Test
    void leavesNoPartialFileBehindWhenTheDumpFails() {
        Path destination = outputDir.resolve("shop.sql.gz");

        assertThatThrownBy(() -> adapter.dumpTo(connection("backup", "wrong-password"), destination))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("Access denied");

        assertThat(destination).doesNotExist();
    }

    @Test
    void reportsMysqldumpsOwnWordsForAnUnreachableServer() {
        assertThatThrownBy(() -> adapter.dumpTo(
                new MysqlConnection(MYSQL.getHost(), 1, "shop", "backup", "s3cr3t"),
                outputDir.resolve("shop.sql.gz")))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("mysqldump exited with");
    }

    @Test
    void neverPutsThePasswordOnTheCommandLine() {
        var command = new MysqlDumpBackupAdapter(new ProcessRunner(), MYSQLDUMP, Duration.ofMinutes(2))
                .command(connection("backup", "s3cr3t"));

        assertThat(command).noneMatch(argument -> argument.contains("s3cr3t"));
    }

    private static String gunzip(Path archive) throws Exception {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
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
