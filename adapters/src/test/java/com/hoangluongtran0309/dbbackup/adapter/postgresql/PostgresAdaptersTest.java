package com.hoangluongtran0309.dbbackup.adapter.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

@ExtendWith(MockitoExtension.class)
class PostgresAdaptersTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(2);
    private static final DatabaseConnection CONNECTION = new DatabaseConnection(
            DatabaseEngine.POSTGRESQL, "db.internal", 5432, "shop", "backup", "top-secret");

    @Mock
    private ProcessRunner runner;

    @TempDir
    Path temporaryDirectory;

    @Test
    void connectionTestChecksServerAndDumpVersionsWithoutPuttingPasswordInArguments() {
        when(runner.run(anyList(), anyMap(), any())).thenReturn(
                new ProcessRunner.Result(0, "170011\n", ""),
                new ProcessRunner.Result(0, "pg_dump (PostgreSQL) 17.11", ""));
        PostgresCliConnectionTestAdapter adapter = new PostgresCliConnectionTestAdapter(
                runner, Path.of("/usr/bin/psql"), Path.of("/usr/bin/pg_dump"), CONNECT_TIMEOUT);

        assertThat(adapter.test(CONNECTION).successful()).isTrue();

        ArgumentCaptor<List<String>> command = commandCaptor();
        ArgumentCaptor<Map<String, String>> environment = environmentCaptor();
        verify(runner, times(2)).run(command.capture(), environment.capture(), any());
        assertThat(command.getAllValues().get(0))
                .contains("--command=SHOW server_version_num", "--no-password")
                .noneMatch(argument -> argument.contains(CONNECTION.password()));
        assertThat(command.getAllValues().get(1)).containsExactly("/usr/bin/pg_dump", "--version");
        assertThat(environment.getAllValues().get(0)).containsEntry("PGPASSWORD", CONNECTION.password());
        assertThat(environment.getAllValues().get(1)).isEmpty();
    }

    @Test
    void connectionTestAllowsAnOlderServer() {
        when(runner.run(anyList(), anyMap(), any())).thenReturn(
                new ProcessRunner.Result(0, "160015", ""),
                new ProcessRunner.Result(0, "pg_dump (PostgreSQL) 17.11 (Ubuntu 17.11-1.pgdg24.04+1)", ""));
        PostgresCliConnectionTestAdapter adapter = connectionTestAdapter();

        assertThat(adapter.test(CONNECTION).successful()).isTrue();
    }

    @Test
    void connectionTestRejectsAServerNewerThanPgDumpWithAnActionableMessage() {
        when(runner.run(anyList(), anyMap(), any())).thenReturn(
                new ProcessRunner.Result(0, "180006", ""),
                new ProcessRunner.Result(0, "pg_dump (PostgreSQL) 17.11", ""));
        PostgresCliConnectionTestAdapter adapter = connectionTestAdapter();

        ConnectionTestPort.Result result = adapter.test(CONNECTION);

        assertThat(result.successful()).isFalse();
        assertThat(result.message())
                .contains("server major 18", "pg_dump major 17", "/usr/bin/pg_dump",
                        "PG_DUMP_PATH", "major 18 or newer");
    }

    @Test
    void connectionTestPreservesPsqlFailureAndDoesNotInspectPgDump() {
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(2, "", "password authentication failed"));
        PostgresCliConnectionTestAdapter adapter = connectionTestAdapter();

        ConnectionTestPort.Result result = adapter.test(CONNECTION);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).isEqualTo("password authentication failed");
        verify(runner).run(anyList(), anyMap(), any());
        verifyNoMoreInteractions(runner);
    }

    @Test
    void connectionTestRejectsUnrecognisedServerVersionOutput() {
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "PostgreSQL seventeen", ""));
        PostgresCliConnectionTestAdapter adapter = connectionTestAdapter();

        ConnectionTestPort.Result result = adapter.test(CONNECTION);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("server version", "PostgreSQL seventeen");
    }

    @Test
    void connectionTestRejectsAFailedPgDumpVersionCommand() {
        when(runner.run(anyList(), anyMap(), any())).thenReturn(
                new ProcessRunner.Result(0, "170011", ""),
                new ProcessRunner.Result(1, "", "broken executable"));
        PostgresCliConnectionTestAdapter adapter = connectionTestAdapter();

        ConnectionTestPort.Result result = adapter.test(CONNECTION);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("configured pg_dump version", "/usr/bin/pg_dump", "broken executable");
    }

    @Test
    void connectionTestRejectsUnrecognisedPgDumpVersionOutput() {
        when(runner.run(anyList(), anyMap(), any())).thenReturn(
                new ProcessRunner.Result(0, "170011", ""),
                new ProcessRunner.Result(0, "not pg_dump", ""));
        PostgresCliConnectionTestAdapter adapter = connectionTestAdapter();

        ConnectionTestPort.Result result = adapter.test(CONNECTION);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("configured pg_dump version", "/usr/bin/pg_dump", "not pg_dump");
    }

    @Test
    void dumpUsesCustomFormatAndDeclaresDumpSuffix() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.dump");
        Files.writeString(artifact, "archive");
        when(runner.run(anyList(), anyMap(), any())).thenReturn(new ProcessRunner.Result(0, "", ""));
        PostgresDumpBackupAdapter adapter = new PostgresDumpBackupAdapter(
                runner, Path.of("/usr/bin/pg_dump"), CONNECT_TIMEOUT, JOB_TIMEOUT);

        assertThat(adapter.artifactSuffix()).isEqualTo(".dump");
        assertThat(adapter.dumpTo(CONNECTION, artifact)).isEqualTo(7);

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), any());
        assertThat(command.getValue()).contains(
                "--format=custom", "--no-owner", "--no-privileges", "--file=" + artifact);
        assertThat(command.getValue()).noneMatch(argument -> argument.contains(CONNECTION.password()));
    }

    @Test
    void failedDumpDeletesItsPartialArtifact() throws Exception {
        Path artifact = temporaryDirectory.resolve("partial.dump");
        Files.writeString(artifact, "partial");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "connection lost"));
        PostgresDumpBackupAdapter adapter = new PostgresDumpBackupAdapter(
                runner, Path.of("/usr/bin/pg_dump"), CONNECT_TIMEOUT, JOB_TIMEOUT);

        assertThatThrownBy(() -> adapter.dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("connection lost");
        assertThat(artifact).doesNotExist();
    }

    @Test
    void timedOutDumpDeletesItsPartialArtifact() throws Exception {
        Path artifact = temporaryDirectory.resolve("timed-out.dump");
        Files.writeString(artifact, "partial");
        when(runner.run(anyList(), anyMap(), any()))
                .thenThrow(new ProcessRunner.ProcessFailedException("Process timed out after PT2M"));
        PostgresDumpBackupAdapter adapter = new PostgresDumpBackupAdapter(
                runner, Path.of("/usr/bin/pg_dump"), CONNECT_TIMEOUT, JOB_TIMEOUT);

        assertThatThrownBy(() -> adapter.dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("timed out");
        assertThat(artifact).doesNotExist();
    }

    @Test
    void restoreValidatesArchiveThenUsesDestructiveRestoreFlags() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.dump");
        Files.writeString(artifact, "archive");
        when(runner.run(anyList(), anyMap(), any())).thenReturn(new ProcessRunner.Result(0, "", ""));
        PostgresRestoreAdapter adapter = new PostgresRestoreAdapter(
                runner, Path.of("/usr/bin/pg_restore"), CONNECT_TIMEOUT, JOB_TIMEOUT);

        adapter.restore(CONNECTION, artifact);

        ArgumentCaptor<List<String>> commands = commandCaptor();
        ArgumentCaptor<Map<String, String>> environments = environmentCaptor();
        verify(runner, times(2)).run(commands.capture(), environments.capture(), any());
        assertThat(commands.getAllValues().get(0)).containsExactly(
                "/usr/bin/pg_restore", "--list", artifact.toString());
        assertThat(commands.getAllValues().get(1)).contains(
                "--clean", "--if-exists", "--no-owner", "--no-privileges", "--exit-on-error");
        assertThat(commands.getAllValues()).allSatisfy(command ->
                assertThat(command).noneMatch(argument -> argument.contains(CONNECTION.password())));
        assertThat(environments.getAllValues()).allSatisfy(environment ->
                assertThat(environment).containsEntry("PGPASSWORD", CONNECTION.password()));
    }

    @Test
    void refusesANonExecutableBinaryAtStartup() {
        Path missing = temporaryDirectory.resolve("pg_dump");

        assertThatThrownBy(() -> new PostgresDumpBackupAdapter(
                runner, missing, CONNECT_TIMEOUT, JOB_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.postgresql.dump-path")
                .hasMessageContaining(missing.toString());
    }

    private PostgresCliConnectionTestAdapter connectionTestAdapter() {
        return new PostgresCliConnectionTestAdapter(
                runner, Path.of("/usr/bin/psql"), Path.of("/usr/bin/pg_dump"), CONNECT_TIMEOUT);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> commandCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, String>> environmentCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
