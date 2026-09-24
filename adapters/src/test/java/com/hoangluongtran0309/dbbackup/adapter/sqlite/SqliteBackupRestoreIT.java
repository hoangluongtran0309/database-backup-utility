package com.hoangluongtran0309.dbbackup.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

/** Real sqlite3 CLI against real files, including a cross-target round trip. */
class SqliteBackupRestoreIT {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @TempDir
    Path root;

    private Path sqlite;
    private ProcessRunner runner;
    private SqliteCliConnectionTestAdapter connectionTest;
    private SqliteDumpBackupAdapter backup;
    private SqliteRestoreAdapter restore;

    @BeforeEach
    void setUp() {
        sqlite = sqliteBinary();
        assertThat(sqlite).isExecutable();
        runner = new ProcessRunner();
        SqliteDatabaseFiles files = new SqliteDatabaseFiles(root);
        connectionTest = new SqliteCliConnectionTestAdapter(runner, files, sqlite, TIMEOUT);
        backup = new SqliteDumpBackupAdapter(runner, files, sqlite, TIMEOUT);
        restore = new SqliteRestoreAdapter(runner, files, sqlite, TIMEOUT);

        sql("source.db", """
                CREATE TABLE widgets(id INTEGER PRIMARY KEY, name TEXT NOT NULL);
                CREATE UNIQUE INDEX ux_widgets_name ON widgets(name);
                CREATE TABLE audit(message TEXT NOT NULL);
                CREATE TRIGGER widgets_audit AFTER INSERT ON widgets
                  BEGIN INSERT INTO audit(message) VALUES ('added:' || NEW.name); END;
                INSERT INTO widgets(name) VALUES ('alpha'), ('O''Brien'), ('Đà Nẵng');
                """);
        sql("destination.db", """
                CREATE TABLE obsolete(id INTEGER);
                INSERT INTO obsolete VALUES (99);
                """);
    }

    @Test
    void testsBacksUpAndRestoresIntoAnotherSqliteFile() throws Exception {
        assertThat(connectionTest.test(connection("source.db")).successful()).isTrue();
        Path artifact = root.resolve("source.sql.gz");

        assertThat(backup.dumpTo(connection("source.db"), artifact)).isPositive();
        assertThat(gunzip(artifact)).contains("CREATE TABLE widgets", "CREATE TRIGGER widgets_audit");

        restore.restore(connection("destination.db"), "source.db", artifact);

        assertThat(query("destination.db", "SELECT group_concat(name, '|') FROM widgets ORDER BY id;"))
                .isEqualTo("alpha|O'Brien|Đà Nẵng");
        assertThat(query("destination.db", "SELECT count(*) FROM sqlite_schema WHERE name='ux_widgets_name';"))
                .isEqualTo("1");
        assertThat(query("destination.db", "SELECT count(*) FROM sqlite_schema WHERE name='obsolete';"))
                .isEqualTo("0");
        sql("destination.db", "INSERT INTO widgets(name) VALUES ('later');");
        assertThat(query("destination.db", "SELECT message FROM audit ORDER BY rowid DESC LIMIT 1;"))
                .isEqualTo("added:later");
        assertThat(query("source.db", "SELECT count(*) FROM widgets;")).isEqualTo("3");
    }

    @Test
    void verifiesARealBackupInAPrivateSqliteFileAndRemovesIt() throws Exception {
        Path artifact = root.resolve("source.sql.gz");
        backup.dumpTo(connection("source.db"), artifact);
        UUID verificationId = UUID.randomUUID();

        var result = new SqliteRestoreVerificationAdapter(
                runner, sqlite, root, TIMEOUT, true)
                .verify(verificationId, sourceTarget(), artifact);

        assertThat(result.checkedObjects()).isEqualTo(2);
        assertThat(root.resolve(".dbbackup-verify-" + verificationId)).doesNotExist();

        UUID corruptId = UUID.randomUUID();
        Path corrupt = Files.writeString(root.resolve("corrupt-verification.sql.gz"), "not gzip");
        assertThatThrownBy(() -> new SqliteRestoreVerificationAdapter(
                runner, sqlite, root, TIMEOUT, true).verify(corruptId, sourceTarget(), corrupt))
                .hasMessageContaining("gzip");
        assertThat(root.resolve(".dbbackup-verify-" + corruptId)).doesNotExist();
    }

    @Test
    void corruptArtifactLeavesDestinationUntouched() throws Exception {
        Path artifact = root.resolve("broken.sql.gz");
        Files.writeString(artifact, "not gzip");

        assertThatThrownBy(() -> restore.restore(connection("destination.db"), artifact))
                .isInstanceOf(RestoreFailedException.class);

        assertThat(query("destination.db", "SELECT id FROM obsolete;")).isEqualTo("99");
    }

    private DatabaseConnection connection(String file) {
        return new DatabaseConnection(DatabaseEngine.SQLITE, null, null, file, null, null);
    }

    private static DatabaseTarget sourceTarget() {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name("source").engine(DatabaseEngine.SQLITE)
                .databaseName("source.db").createdAt(Instant.EPOCH).build();
    }

    private void sql(String file, String statement) {
        ProcessRunner.Result result = runner.run(
                List.of(sqlite.toString(), root.resolve(file).toString(), statement), Map.of(), TIMEOUT);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
    }

    private String query(String file, String statement) {
        ProcessRunner.Result result = runner.run(
                List.of(sqlite.toString(), "-noheader", root.resolve(file).toString(), statement),
                Map.of(), TIMEOUT);
        assertThat(result.exitCode()).as(result.stderr()).isZero();
        return result.stdout().strip();
    }

    private static String gunzip(Path artifact) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(Files.newInputStream(artifact))) {
            return new String(input.readAllBytes());
        }
    }

    private static Path sqliteBinary() {
        String configured = System.getenv("SQLITE_PATH");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        Path system = Path.of("/usr/bin/sqlite3");
        return Files.isExecutable(system)
                ? system
                : Path.of("/home/linuxbrew/.linuxbrew/bin/sqlite3");
    }
}
