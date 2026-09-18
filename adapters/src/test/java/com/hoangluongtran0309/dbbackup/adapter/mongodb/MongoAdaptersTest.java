package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
class MongoAdaptersTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(2);
    private static final String PASSWORD = "p\"a\\ss\nĐà Nẵng";
    private static final DatabaseConnection CONNECTION = new DatabaseConnection(
            DatabaseEngine.MONGODB, "mongo.internal", 27017, "shop", "backup", PASSWORD, "admin");

    @Mock
    private ProcessRunner runner;

    @TempDir
    Path temporaryDirectory;

    @Test
    void connectionTestUsesAProbeCollectionAndAnOwnerOnlyCredentialFile() throws Exception {
        AtomicReference<Path> configSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(call -> {
            List<String> command = call.getArgument(0);
            Path config = configPath(command);
            configSeen.set(config);
            assertThat(Files.getPosixFilePermissions(config)).isEqualTo(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            assertThat(Files.readString(config))
                    .isEqualTo("password: \"p\\\"a\\\\ss\\nĐà Nẵng\"\n");
            return new ProcessRunner.Result(0, "archive header", "");
        });
        MongoCliConnectionTestAdapter adapter = new MongoCliConnectionTestAdapter(
                runner, Path.of("/usr/bin/mongodump"), CONNECT_TIMEOUT);

        assertThat(adapter.test(CONNECTION).successful()).isTrue();

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), any());
        assertThat(command.getValue()).contains(
                "--host=mongo.internal", "--port=27017", "--username=backup",
                "--authenticationDatabase=admin", "--db=shop", "--archive");
        assertThat(command.getValue()).anyMatch(value -> value.startsWith(
                "--collection=__dbbackup_connection_probe_"));
        assertThat(command.getValue()).noneMatch(value -> value.contains(PASSWORD));
        assertThat(configSeen.get()).doesNotExist();
    }

    @Test
    void connectionFailureReturnsTheMongoToolMessage() {
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "Authentication failed for backup"));
        MongoCliConnectionTestAdapter adapter = new MongoCliConnectionTestAdapter(
                runner, Path.of("/usr/bin/mongodump"), CONNECT_TIMEOUT);

        assertThat(adapter.test(CONNECTION))
                .extracting(result -> result.successful(), result -> result.message())
                .containsExactly(false, "Authentication failed for backup");
    }

    @Test
    void dumpWritesAGzippedArchiveAndDeclaresItsSuffix() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.archive.gz");
        AtomicReference<Path> configSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(call -> {
            List<String> command = call.getArgument(0);
            configSeen.set(configPath(command));
            Files.writeString(artifact, "archive");
            return new ProcessRunner.Result(0, "", "");
        });
        MongoDumpBackupAdapter adapter = new MongoDumpBackupAdapter(
                runner, Path.of("/usr/bin/mongodump"), JOB_TIMEOUT);

        assertThat(adapter.artifactSuffix()).isEqualTo(".archive.gz");
        assertThat(adapter.dumpTo(CONNECTION, artifact)).isEqualTo(7);

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), any());
        assertThat(command.getValue()).contains("--db=shop", "--gzip", "--archive=" + artifact);
        assertThat(command.getValue()).noneMatch(value -> value.contains(PASSWORD));
        assertThat(configSeen.get()).doesNotExist();
    }

    @Test
    void failedDumpDeletesItsPartialArtifact() throws Exception {
        Path artifact = temporaryDirectory.resolve("partial.archive.gz");
        Files.writeString(artifact, "partial");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "authentication failed"));
        MongoDumpBackupAdapter adapter = new MongoDumpBackupAdapter(
                runner, Path.of("/usr/bin/mongodump"), JOB_TIMEOUT);

        assertThatThrownBy(() -> adapter.dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("authentication failed");
        assertThat(artifact).doesNotExist();
    }

    @Test
    void timedOutDumpDeletesItsPartialArtifactAndCredentialFile() {
        Path artifact = temporaryDirectory.resolve("timed-out.archive.gz");
        AtomicReference<Path> configSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(call -> {
            Files.writeString(artifact, "partial");
            configSeen.set(configPath(call.getArgument(0)));
            throw new ProcessRunner.ProcessFailedException("mongodump timed out");
        });
        MongoDumpBackupAdapter adapter = new MongoDumpBackupAdapter(
                runner, Path.of("/usr/bin/mongodump"), JOB_TIMEOUT);

        assertThatThrownBy(() -> adapter.dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("timed out");
        assertThat(artifact).doesNotExist();
        assertThat(configSeen.get()).doesNotExist();
    }

    @Test
    void restoreDryRunsBeforeChangingNamespacesAndDroppingCollections() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.archive.gz");
        Files.writeString(artifact, "archive");
        when(runner.run(anyList(), anyMap(), any())).thenReturn(new ProcessRunner.Result(0, "", ""));
        MongoRestoreAdapter adapter = new MongoRestoreAdapter(
                runner, Path.of("/usr/bin/mongorestore"), JOB_TIMEOUT);

        adapter.restore(CONNECTION, "source_shop", artifact);

        ArgumentCaptor<List<String>> commands = commandCaptor();
        verify(runner, times(2)).run(commands.capture(), anyMap(), any());
        List<String> dryRun = commands.getAllValues().get(0);
        List<String> restore = commands.getAllValues().get(1);
        assertThat(dryRun).contains(
                "--archive=" + artifact, "--gzip", "--nsInclude=source_shop.*",
                "--nsFrom=source_shop.*", "--nsTo=shop.*", "--dryRun");
        assertThat(dryRun).doesNotContain("--drop", "--stopOnError");
        assertThat(restore).contains("--drop", "--stopOnError");
        assertThat(restore).doesNotContain("--dryRun");
        assertThat(commands.getAllValues()).allSatisfy(command -> {
            assertThat(command).noneMatch(value -> value.contains(PASSWORD));
            assertThat(configPath(command)).doesNotExist();
        });
    }

    @Test
    void failedDryRunNeverTouchesTheDestination() throws Exception {
        Path artifact = temporaryDirectory.resolve("invalid.archive.gz");
        Files.writeString(artifact, "not an archive");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "invalid BSON archive"));
        MongoRestoreAdapter adapter = new MongoRestoreAdapter(
                runner, Path.of("/usr/bin/mongorestore"), JOB_TIMEOUT);

        assertThatThrownBy(() -> adapter.restore(CONNECTION, "source_shop", artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("invalid BSON archive");

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner, times(1)).run(command.capture(), anyMap(), any());
        assertThat(command.getValue()).contains("--dryRun").doesNotContain("--drop");
    }

    @Test
    void refusesANonExecutableBinaryAtStartup() {
        Path missing = temporaryDirectory.resolve("mongodump");

        assertThatThrownBy(() -> new MongoDumpBackupAdapter(runner, missing, JOB_TIMEOUT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.mongodb.dump-path")
                .hasMessageContaining(missing.toString());
    }

    private static Path configPath(List<String> command) {
        return Path.of(command.stream()
                .filter(value -> value.startsWith("--config="))
                .findFirst()
                .orElseThrow()
                .substring("--config=".length()));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> commandCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
