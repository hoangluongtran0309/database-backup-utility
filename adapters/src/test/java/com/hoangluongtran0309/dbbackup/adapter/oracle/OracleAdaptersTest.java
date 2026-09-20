package com.hoangluongtran0309.dbbackup.adapter.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
class OracleAdaptersTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final Path BINARY = Path.of("/bin/true");
    private static final String PASSWORD = "top-secret";
    private static final DatabaseConnection CONNECTION = new DatabaseConnection(
            DatabaseEngine.ORACLE, "oracle.internal", 1521, "FREEPDB1",
            "APP_OWNER", PASSWORD, null, "DBBACKUP_PUMP_DIR");

    @Mock
    private ProcessRunner runner;

    @TempDir
    Path temporaryDirectory;

    @Test
    void connectionProbeKeepsPasswordOutOfArgumentsAndVerifiesTheSharedDirectory() throws Exception {
        OracleDataPumpFiles files = new OracleDataPumpFiles(temporaryDirectory);
        when(runner.runFeeding(anyList(), anyMap(), any(), any(InputStream.class))).thenAnswer(call -> {
            String input = readInput(call);
            assertThat(input).startsWith(
                    "CONNECT APP_OWNER/\"" + PASSWORD + "\"@//oracle.internal:1521/FREEPDB1\n");
            Matcher filename = Pattern.compile("dbbackup-probe-[0-9a-f]+\\.tmp").matcher(input);
            assertThat(filename.find()).isTrue();
            Files.writeString(temporaryDirectory.resolve(filename.group()), "probe");
            return success();
        });

        OracleCliConnectionTestAdapter adapter = new OracleCliConnectionTestAdapter(
                runner, files, BINARY, TIMEOUT);

        assertThat(adapter.test(CONNECTION).successful()).isTrue();

        ArgumentCaptor<List<String>> commands = commandCaptor();
        verify(runner).runFeeding(commands.capture(), anyMap(), any(), any(InputStream.class));
        assertSafeCommand(commands.getValue());
        assertThat(commands.getValue()).containsExactly(BINARY.toString(), "-L", "-S", "/NOLOG");
        assertThat(temporaryDirectory).isEmptyDirectory();
    }

    @Test
    void backupUsesSchemaModeAndMovesTheCompletedDumpOutOfStaging() throws Exception {
        UUID executionId = UUID.randomUUID();
        Path artifact = temporaryDirectory.resolve("artifacts").resolve("app.dmp");
        Files.createDirectories(artifact.getParent());
        OracleDataPumpFiles files = new OracleDataPumpFiles(temporaryDirectory);
        when(runner.runFeeding(anyList(), anyMap(), any(), any(InputStream.class))).thenAnswer(call -> {
            List<String> command = call.getArgument(0);
            String dump = argument(command, "DUMPFILE=");
            Files.writeString(temporaryDirectory.resolve(dump), "oracle archive");
            assertThat(readInput(call)).startsWith(PASSWORD + "\n");
            return success();
        });
        OracleDataPumpBackupAdapter adapter = backupAdapter(files);

        assertThat(adapter.dumpTo(CONNECTION, artifact, executionId)).isEqualTo(14);
        assertThat(Files.readString(artifact)).isEqualTo("oracle archive");

        ArgumentCaptor<List<String>> commands = commandCaptor();
        verify(runner).runFeeding(commands.capture(), anyMap(), any(), any(InputStream.class));
        assertThat(commands.getValue()).contains(
                "DIRECTORY=DBBACKUP_PUMP_DIR",
                "SCHEMAS=APP_OWNER",
                "JOB_NAME=" + OracleDataPumpJobs.exportJob(executionId),
                "NOLOGFILE=YES");
        assertSafeCommand(commands.getValue());
        assertThat(files.backupDump(executionId)).doesNotExist();
    }

    @Test
    void timedOutBackupAttachesAndKillsItsStableJobBeforeCleaningStaging() throws Exception {
        UUID executionId = UUID.randomUUID();
        OracleDataPumpFiles files = new OracleDataPumpFiles(temporaryDirectory);
        List<List<String>> seen = new ArrayList<>();
        when(runner.runFeeding(anyList(), anyMap(), any(), any(InputStream.class))).thenAnswer(invocation -> {
            List<String> command = List.copyOf(invocation.getArgument(0));
            seen.add(command);
            Files.writeString(files.backupDump(executionId), "partial");
            throw new ProcessRunner.ProcessFailedException("expdp timed out");
        });
        when(runner.runResponding(anyList(), anyMap(), any(), anyList())).thenAnswer(invocation -> {
            seen.add(List.copyOf(invocation.getArgument(0)));
            assertKillResponses(invocation);
            return new ProcessRunner.Result(1, "", "ORA-31626: job does not exist");
        });

        assertThatThrownBy(() -> backupAdapter(files).dumpTo(
                CONNECTION, temporaryDirectory.resolve("partial.dmp"), executionId))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("timed out");

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).contains("ATTACH=" + OracleDataPumpJobs.exportJob(executionId));
        assertThat(files.backupDump(executionId)).doesNotExist();
    }

    @Test
    void failedBackupPreservesStagingWhenJobTerminationCannotBeConfirmed() throws Exception {
        UUID executionId = UUID.randomUUID();
        OracleDataPumpFiles files = new OracleDataPumpFiles(temporaryDirectory);
        when(runner.runFeeding(anyList(), anyMap(), any(), any(InputStream.class))).thenAnswer(invocation -> {
            Files.writeString(files.backupDump(executionId), "partial");
            return new ProcessRunner.Result(1, "", "connection lost");
        });
        when(runner.runResponding(anyList(), anyMap(), any(), anyList())).thenAnswer(invocation -> {
            throw new ProcessRunner.ProcessFailedException("cannot attach");
        });

        assertThatThrownBy(() -> backupAdapter(files).dumpTo(
                CONNECTION, temporaryDirectory.resolve("partial.dmp"), executionId))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("staging was preserved");
        assertThat(files.backupDump(executionId)).exists();
    }

    @Test
    void restorePreflightsThenImportsWithSafeReplacementAndSchemaRemap() throws Exception {
        UUID executionId = UUID.randomUUID();
        Path artifact = temporaryDirectory.resolve("source.dmp");
        Files.writeString(artifact, "valid data pump archive");
        OracleDataPumpFiles files = new OracleDataPumpFiles(temporaryDirectory);
        List<List<String>> seen = new ArrayList<>();
        when(runner.runFeeding(anyList(), anyMap(), any(), any(InputStream.class))).thenAnswer(call -> {
            List<String> command = List.copyOf(call.getArgument(0));
            seen.add(command);
            command.stream().filter(value -> value.startsWith("SQLFILE="))
                    .findFirst()
                    .ifPresent(value -> write(temporaryDirectory.resolve(value.substring("SQLFILE=".length())), "DDL"));
            assertThat(readInput(call)).startsWith(PASSWORD + "\n");
            return success();
        });

        restoreAdapter(files).restore(CONNECTION, "SOURCE_OWNER", artifact, executionId);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(0)).contains(
                "SCHEMAS=SOURCE_OWNER",
                "JOB_NAME=" + OracleDataPumpJobs.sqlJob(executionId),
                "REMAP_SCHEMA=SOURCE_OWNER:APP_OWNER",
                "SQLFILE=" + files.sqlFile(executionId).getFileName());
        assertThat(seen.get(0)).doesNotContain("TABLE_EXISTS_ACTION=REPLACE");
        assertThat(seen.get(1)).contains(
                "TABLE_EXISTS_ACTION=REPLACE",
                "TRANSFORM=OID:N",
                "TRANSFORM=SEGMENT_ATTRIBUTES:N",
                "REMAP_SCHEMA=SOURCE_OWNER:APP_OWNER",
                "JOB_NAME=" + OracleDataPumpJobs.importJob(executionId));
        seen.forEach(OracleAdaptersTest::assertSafeCommand);
        assertThat(files.restoreDump(executionId)).doesNotExist();
        assertThat(files.sqlFile(executionId)).doesNotExist();
    }

    @Test
    void failedPreflightNeverRunsTheDestructiveImport() throws Exception {
        UUID executionId = UUID.randomUUID();
        Path artifact = temporaryDirectory.resolve("invalid.dmp");
        Files.writeString(artifact, "not a dump");
        OracleDataPumpFiles files = new OracleDataPumpFiles(temporaryDirectory);
        List<List<String>> seen = new ArrayList<>();
        when(runner.runFeeding(anyList(), anyMap(), any(), any(InputStream.class))).thenAnswer(invocation -> {
            seen.add(List.copyOf(invocation.getArgument(0)));
            return new ProcessRunner.Result(1, "", "invalid Data Pump file");
        });
        when(runner.runResponding(anyList(), anyMap(), any(), anyList())).thenAnswer(invocation -> {
            seen.add(List.copyOf(invocation.getArgument(0)));
            assertKillResponses(invocation);
            return new ProcessRunner.Result(1, "", "ORA-31626: job does not exist");
        });

        assertThatThrownBy(() -> restoreAdapter(files).restore(
                CONNECTION, "SOURCE_OWNER", artifact, executionId))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("preflight failed")
                .hasMessageContaining("invalid Data Pump file");

        assertThat(seen).hasSize(3);
        assertThat(seen).allSatisfy(command -> assertThat(command)
                .doesNotContain("TABLE_EXISTS_ACTION=REPLACE"));
        assertThat(files.restoreDump(executionId)).doesNotExist();
    }

    private OracleDataPumpBackupAdapter backupAdapter(OracleDataPumpFiles files) {
        return new OracleDataPumpBackupAdapter(runner, files, jobs(files), BINARY, TIMEOUT);
    }

    private OracleDataPumpRestoreAdapter restoreAdapter(OracleDataPumpFiles files) {
        return new OracleDataPumpRestoreAdapter(runner, files, jobs(files), BINARY, TIMEOUT);
    }

    private OracleDataPumpJobs jobs(OracleDataPumpFiles ignored) {
        return new OracleDataPumpJobs(runner, BINARY, BINARY, TIMEOUT);
    }

    private static ProcessRunner.Result success() {
        return new ProcessRunner.Result(0, "", "");
    }

    private static String argument(List<String> command, String prefix) {
        return command.stream().filter(value -> value.startsWith(prefix)).findFirst().orElseThrow()
                .substring(prefix.length());
    }

    private static String readInput(org.mockito.invocation.InvocationOnMock call) throws Exception {
        InputStream input = call.getArgument(3);
        return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void assertKillResponses(org.mockito.invocation.InvocationOnMock call) {
        List<ProcessRunner.PromptResponse> responses = call.getArgument(3);
        assertThat(responses).containsExactly(
                new ProcessRunner.PromptResponse("Password:", PASSWORD),
                new ProcessRunner.PromptResponse(">", "KILL_JOB"),
                new ProcessRunner.PromptResponse("Are you sure", "YES"));
    }

    private static void write(Path path, String value) {
        try {
            Files.writeString(path, value);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void assertSafeCommand(List<String> command) {
        assertThat(command).noneMatch(value -> value.contains(PASSWORD));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> commandCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }
}
