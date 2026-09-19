package com.hoangluongtran0309.dbbackup.adapter.sqlite;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

@ExtendWith(MockitoExtension.class)
class SqliteAdaptersTest {

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    @TempDir
    Path root;

    @Mock
    ProcessRunner runner;

    @Test
    void resolverAcceptsNestedFilesAndRejectsTraversalAndEscapingSymlinks() throws Exception {
        Path nested = Files.createDirectories(root.resolve("apps")).resolve("shop.db");
        Files.writeString(nested, "database");
        SqliteDatabaseFiles files = new SqliteDatabaseFiles(root);

        assertThat(files.resolve("apps/shop.db")).isEqualTo(nested.toRealPath());
        assertThatThrownBy(() -> files.resolve("../outside.db"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside SQLITE_ROOT");

        Path outside = Files.createTempFile("dbbackup-outside-", ".db");
        try {
            Files.createSymbolicLink(root.resolve("escape.db"), outside);
            assertThatThrownBy(() -> files.resolve("escape.db"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outside SQLITE_ROOT");
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void connectionTestRequiresQuickCheckToReturnExactlyOk() throws Exception {
        Files.writeString(root.resolve("shop.db"), "database");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "wrong\n", ""));
        SqliteCliConnectionTestAdapter adapter = new SqliteCliConnectionTestAdapter(
                runner, new SqliteDatabaseFiles(root), executable(), TIMEOUT);

        assertThat(adapter.test(connection()).successful()).isFalse();

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), any());
        assertThat(command.getValue()).contains("-readonly", "PRAGMA quick_check;");
    }

    @Test
    void backupChecksIntegrityThenStreamsAGzippedDump() throws Exception {
        Files.writeString(root.resolve("shop.db"), "database");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "ok\n", ""));
        when(runner.runStreaming(anyList(), anyMap(), any(), any(OutputStream.class))).thenAnswer(call -> {
            call.<OutputStream>getArgument(3).write("CREATE TABLE widget(id);".getBytes());
            return new ProcessRunner.Result(0, "", "");
        });
        SqliteDumpBackupAdapter adapter = new SqliteDumpBackupAdapter(
                runner, new SqliteDatabaseFiles(root), executable(), TIMEOUT);
        Path artifact = root.resolve("shop.sql.gz");

        assertThat(adapter.dumpTo(connection(), artifact)).isPositive();
        assertThat(artifact).isNotEmptyFile();
        assertThat(adapter.artifactSuffix()).isEqualTo(".sql.gz");
    }

    @Test
    void backupDeletesPartialArtifactWhenDumpWritesToStderr() throws Exception {
        Files.writeString(root.resolve("shop.db"), "database");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "ok\n", ""));
        when(runner.runStreaming(anyList(), anyMap(), any(), any(OutputStream.class))).thenAnswer(call -> {
            call.<OutputStream>getArgument(3).write("partial".getBytes());
            return new ProcessRunner.Result(0, "", "database is locked");
        });
        SqliteDumpBackupAdapter adapter = new SqliteDumpBackupAdapter(
                runner, new SqliteDatabaseFiles(root), executable(), TIMEOUT);
        Path artifact = root.resolve("partial.sql.gz");

        assertThatThrownBy(() -> adapter.dumpTo(connection(), artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("database is locked");
        assertThat(artifact).doesNotExist();
    }

    @Test
    void failedTemporaryIntegrityCheckNeverRestoresTheDestination() throws Exception {
        Files.writeString(root.resolve("shop.db"), "original");
        Path artifact = root.resolve("shop.sql.gz");
        try (GZIPOutputStream gzip = new GZIPOutputStream(Files.newOutputStream(artifact))) {
            gzip.write("CREATE TABLE widget(id);".getBytes());
        }
        when(runner.runFeeding(anyList(), anyMap(), any(), any()))
                .thenReturn(new ProcessRunner.Result(0, "", ""));
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "not ok", ""));
        SqliteRestoreAdapter adapter = new SqliteRestoreAdapter(
                runner, new SqliteDatabaseFiles(root), executable(), TIMEOUT);

        assertThatThrownBy(() -> adapter.restore(connection(), artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("integrity_check");
        verify(runner, times(1)).run(anyList(), anyMap(), any());
        assertThat(Files.readString(root.resolve("shop.db"))).isEqualTo("original");
    }

    @Test
    void missingBinaryFailsAtStartup() {
        assertThatThrownBy(() -> new SqliteDumpBackupAdapter(
                runner, new SqliteDatabaseFiles(root), root.resolve("missing"), TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.sqlite.client-path");
        verify(runner, never()).run(anyList(), anyMap(), any());
    }

    private DatabaseConnection connection() {
        return new DatabaseConnection(DatabaseEngine.SQLITE, null, null, "shop.db", null, null);
    }

    private static Path executable() {
        return Path.of(System.getProperty("java.home"), "bin", "java");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> commandCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
