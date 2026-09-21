package com.hoangluongtran0309.dbbackup.adapter.mariadb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

@ExtendWith(MockitoExtension.class)
class MariaDbAdaptersTest {

    private static final Path BINARY = Path.of("/bin/sh");
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(2);
    private static final DatabaseConnection CONNECTION = new DatabaseConnection(
            DatabaseEngine.MARIADB, "mariadb.internal", 3307, "shop", "backup", "top-secret");

    @Mock
    private ProcessRunner processRunner;

    @Captor
    private ArgumentCaptor<List<String>> command;

    @Captor
    private ArgumentCaptor<Map<String, String>> environment;

    @TempDir
    Path artifacts;

    private MariaDbClient client;
    private MariaDbDumpBackupAdapter backup;
    private MariaDbRestoreAdapter restore;

    @BeforeEach
    void setUp() {
        client = new MariaDbClient(processRunner, BINARY, CONNECT_TIMEOUT);
        backup = new MariaDbDumpBackupAdapter(processRunner, BINARY, JOB_TIMEOUT);
        restore = new MariaDbRestoreAdapter(processRunner, BINARY, CONNECT_TIMEOUT, JOB_TIMEOUT);
    }

    @Test
    void everyAdapterDeclaresMariaDb() {
        assertThat(new MariaDbCliConnectionTestAdapter(client).engine()).isEqualTo(DatabaseEngine.MARIADB);
        assertThat(backup.engine()).isEqualTo(DatabaseEngine.MARIADB);
        assertThat(restore.engine()).isEqualTo(DatabaseEngine.MARIADB);
        assertThat(backup.artifactSuffix()).isEqualTo(".sql.gz");
    }

    @Test
    void connectionTestUsesMariaDbOverTcpAndKeepsThePasswordOutOfArgv() {
        when(processRunner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "1\n", ""));

        assertThat(new MariaDbCliConnectionTestAdapter(client).test(CONNECTION).successful()).isTrue();

        verify(processRunner).run(command.capture(), environment.capture(), any());
        assertThat(command.getValue()).containsExactly(
                "/bin/sh",
                "--protocol=TCP",
                "--host=mariadb.internal",
                "--port=3307",
                "--user=backup",
                "--connect-timeout=10",
                "--batch",
                "--skip-column-names",
                "--execute=SELECT 1",
                "shop");
        assertThat(command.getValue()).noneMatch(argument -> argument.contains("top-secret"));
        assertThat(environment.getValue()).containsExactlyEntriesOf(Map.of("MYSQL_PWD", "top-secret"));
    }

    @Test
    void connectionTestReturnsTheClientsFailureAndUsesALongerRunnerTimeout() {
        when(processRunner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "Access denied"));
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);

        var result = new MariaDbCliConnectionTestAdapter(client).test(CONNECTION);

        assertThat(result).extracting("successful", "message")
                .containsExactly(false, "Access denied");
        verify(processRunner).run(anyList(), anyMap(), timeout.capture());
        assertThat(timeout.getValue()).isGreaterThan(CONNECT_TIMEOUT);
    }

    @Test
    void dumpUsesMariaDbsOwnOptionsAndProducesAValidGzip() throws Exception {
        when(processRunner.runStreaming(anyList(), anyMap(), any(), any())).thenAnswer(invocation -> {
            OutputStream sink = invocation.getArgument(3);
            sink.write("CREATE TABLE orders (id INT);\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new ProcessRunner.Result(0, "", "");
        });
        Path artifact = artifacts.resolve("shop.sql.gz");

        long size = backup.dumpTo(CONNECTION, artifact);

        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(processRunner).runStreaming(command.capture(), environment.capture(), timeout.capture(), any());
        assertThat(command.getValue()).containsExactly(
                "/bin/sh",
                "--protocol=TCP",
                "--host=mariadb.internal",
                "--port=3307",
                "--user=backup",
                "--single-transaction",
                "--routines",
                "--triggers",
                "--events",
                "shop");
        assertThat(command.getValue()).doesNotContain("--set-gtid-purged=OFF");
        assertThat(command.getValue()).noneMatch(argument -> argument.contains("top-secret"));
        assertThat(environment.getValue()).containsEntry("MYSQL_PWD", "top-secret");
        assertThat(timeout.getValue()).isEqualTo(JOB_TIMEOUT);
        assertThat(size).isEqualTo(Files.size(artifact)).isPositive();
        try (InputStream in = new java.util.zip.GZIPInputStream(Files.newInputStream(artifact))) {
            assertThat(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("CREATE TABLE orders");
        }
    }

    @Test
    void failedDumpRemovesItsPartialArtifactAndKeepsTheClientMessage() {
        when(processRunner.runStreaming(anyList(), anyMap(), any(), any()))
                .thenReturn(new ProcessRunner.Result(2, "", "Access denied"));
        Path artifact = artifacts.resolve("shop.sql.gz");

        assertThatThrownBy(() -> backup.dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("mariadb-dump exited with 2")
                .hasMessageContaining("Access denied");
        assertThat(artifact).doesNotExist();
    }

    @Test
    void restoreChecksTheWholeArchiveThenStreamsItToMariaDb() throws Exception {
        Path artifact = gzip("CREATE TABLE orders (id INT);\n");
        when(processRunner.runFeeding(anyList(), anyMap(), any(), any()))
                .thenReturn(new ProcessRunner.Result(0, "", ""));

        restore.restore(CONNECTION, "source", artifact);

        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(processRunner).runFeeding(command.capture(), environment.capture(), timeout.capture(), any());
        assertThat(command.getValue()).containsExactly(
                "/bin/sh",
                "--protocol=TCP",
                "--host=mariadb.internal",
                "--port=3307",
                "--user=backup",
                "--connect-timeout=10",
                "--batch",
                "shop");
        assertThat(command.getValue()).noneMatch(argument -> argument.contains("top-secret"));
        assertThat(environment.getValue()).containsEntry("MYSQL_PWD", "top-secret");
        assertThat(timeout.getValue()).isEqualTo(JOB_TIMEOUT);
    }

    @Test
    void corruptArchiveNeverStartsTheClient() throws Exception {
        Path artifact = Files.writeString(artifacts.resolve("corrupt.sql.gz"), "not gzip");

        assertThatThrownBy(() -> restore.restore(CONNECTION, "shop", artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Nothing was restored");
        verify(processRunner, never()).runFeeding(anyList(), anyMap(), any(), any());
    }

    @Test
    void truncatedArchiveNeverStartsTheClient() throws Exception {
        Path artifact = gzip("x".repeat(200_000));
        byte[] whole = Files.readAllBytes(artifact);
        Files.write(artifact, java.util.Arrays.copyOf(whole, whole.length / 2));

        assertThatThrownBy(() -> restore.restore(CONNECTION, "shop", artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("Nothing was restored");
        verify(processRunner, never()).runFeeding(anyList(), anyMap(), any(), any());
    }

    @Test
    void refusesMissingConfiguredBinariesAtStartup() {
        assertThatThrownBy(() -> new MariaDbClient(
                processRunner, Path.of("/missing/mariadb"), CONNECT_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.mariadb.client-path")
                .hasMessageContaining("/missing/mariadb");
        assertThatThrownBy(() -> new MariaDbDumpBackupAdapter(
                processRunner, Path.of("/missing/mariadb-dump"), JOB_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.mariadb.dump-path")
                .hasMessageContaining("/missing/mariadb-dump");
        assertThatThrownBy(() -> new MariaDbRestoreAdapter(
                processRunner, Path.of("/missing/mariadb"), CONNECT_TIMEOUT, JOB_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.mariadb.client-path")
                .hasMessageContaining("/missing/mariadb");
    }

    private Path gzip(String sql) throws Exception {
        Path artifact = artifacts.resolve("shop.sql.gz");
        try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(artifact))) {
            out.write(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return artifact;
    }
}
