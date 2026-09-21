package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
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
class SqlServerAdaptersTest {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(7);
    private static final Duration JOB_TIMEOUT = Duration.ofMinutes(2);
    private static final String PASSWORD = "p a\\ss\"Đà-Nẵng!";
    private static final DatabaseConnection CONNECTION = new DatabaseConnection(
            DatabaseEngine.SQLSERVER, "sql.internal", 1433, "shop", "backup", PASSWORD);

    @Mock
    private ProcessRunner runner;

    @TempDir
    Path temporaryDirectory;

    private Path binary;
    private SqlServerTemporaryFiles temporaryFiles;

    @BeforeEach
    void setUp() throws Exception {
        binary = temporaryDirectory.resolve("client");
        Files.writeString(binary, "#!/bin/sh\nexit 0\n");
        binary.toFile().setExecutable(true);
        temporaryFiles = new SqlServerTemporaryFiles(temporaryDirectory.resolve("scratch"));
    }

    @Test
    void connectionTestUsesSqlcmdPasswordEnvironmentAndValidatedTls() {
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "1", ""));
        SqlServerCliConnectionTestAdapter adapter = new SqlServerCliConnectionTestAdapter(
                runner, binary, CONNECT_TIMEOUT, false);

        assertThat(adapter.test(CONNECTION).successful()).isTrue();

        ArgumentCaptor<List<String>> command = commandCaptor();
        ArgumentCaptor<Map<String, String>> environment = mapCaptor();
        verify(runner).run(command.capture(), environment.capture(), eq(CONNECT_TIMEOUT.plusSeconds(5)));
        assertThat(command.getValue()).contains(
                "-S", "tcp:sql.internal,1433", "-d", "shop", "-U", "backup",
                "-N", "-b", "-l", "7", "-Q", "SET NOCOUNT ON; SELECT 1");
        assertThat(command.getValue()).doesNotContain("-C").noneMatch(value -> value.contains(PASSWORD));
        assertThat(environment.getValue()).containsEntry("SQLCMDPASSWORD", PASSWORD);
    }

    @Test
    void connectionTestCanExplicitlyTrustASelfSignedCertificate() {
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "1", ""));
        SqlServerCliConnectionTestAdapter adapter = new SqlServerCliConnectionTestAdapter(
                runner, binary, CONNECT_TIMEOUT, true);

        adapter.test(CONNECTION);

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), eq(CONNECT_TIMEOUT.plusSeconds(5)));
        assertThat(command.getValue()).contains("-N", "-C");
    }

    @Test
    void exportUsesAnOwnerOnlyResponseFileAndCleansEveryTemporaryFile() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.bacpac");
        AtomicReference<Path> responseSeen = new AtomicReference<>();
        AtomicReference<Path> scratchSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(call -> {
            List<String> command = call.getArgument(0);
            Path response = responsePath(command);
            responseSeen.set(response);
            scratchSeen.set(Path.of(environment(call).get("TMPDIR")));
            assertThat(Files.getPosixFilePermissions(response)).isEqualTo(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            assertThat(Files.readString(response))
                    .contains("/SourcePassword:")
                    .contains("p a\\ss" + '\\' + "\"\"Đà-Nẵng")
                    .doesNotContain("\n\n");
            Files.writeString(artifact, "bacpac");
            return new ProcessRunner.Result(0, "exported", "");
        });
        SqlServerBacpacBackupAdapter adapter = backup(false);

        assertThat(adapter.artifactSuffix()).isEqualTo(".bacpac");
        assertThat(adapter.dumpTo(CONNECTION, artifact)).isEqualTo(6);

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), eq(JOB_TIMEOUT));
        assertThat(command.getValue()).contains(
                "/Action:Export",
                "/TargetFile:" + artifact,
                "/SourceServerName:tcp:sql.internal,1433",
                "/SourceDatabaseName:shop",
                "/SourceUser:backup",
                "/SourceEncryptConnection:True",
                "/SourceTrustServerCertificate:false",
                "/SourceTimeout:7",
                "/p:CommandTimeout=0",
                "/p:LongRunningCommandTimeout=0",
                "/p:VerifyExtraction=True");
        assertThat(command.getValue()).noneMatch(value -> value.contains(PASSWORD));
        assertThat(responseSeen.get()).doesNotExist();
        assertThat(scratchSeen.get()).doesNotExist();
    }

    @Test
    void failedExportDeletesThePartialArtifactAndSecretFile() throws Exception {
        Path artifact = temporaryDirectory.resolve("partial.bacpac");
        AtomicReference<Path> responseSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(call -> {
            Files.writeString(artifact, "partial");
            responseSeen.set(responsePath(call.getArgument(0)));
            return new ProcessRunner.Result(1, "", "Login failed");
        });

        assertThatThrownBy(() -> backup(false).dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("Login failed");
        assertThat(artifact).doesNotExist();
        assertThat(responseSeen.get()).doesNotExist();
        assertThat(temporaryDirectory.resolve("scratch")).isEmptyDirectory();
    }

    @Test
    void timedOutExportDeletesItsPartialArtifactResponseFileAndScratchDirectory() throws Exception {
        Path artifact = temporaryDirectory.resolve("timed-out.bacpac");
        AtomicReference<Path> responseSeen = new AtomicReference<>();
        AtomicReference<Path> scratchSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), eq(JOB_TIMEOUT))).thenAnswer(call -> {
            Files.writeString(artifact, "partial");
            responseSeen.set(responsePath(call.getArgument(0)));
            scratchSeen.set(Path.of(environment(call).get("TMPDIR")));
            throw new ProcessRunner.ProcessFailedException("Process timed out after PT2M");
        });

        assertThatThrownBy(() -> backup(false).dumpTo(CONNECTION, artifact))
                .isInstanceOf(BackupFailedException.class)
                .hasMessageContaining("timed out");
        assertThat(artifact).doesNotExist();
        assertThat(responseSeen.get()).doesNotExist();
        assertThat(scratchSeen.get()).doesNotExist();
    }

    @Test
    void importUsesAnOwnerOnlyTargetPasswordFileAndTheOuterTimeout() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.bacpac");
        Files.writeString(artifact, "bacpac");
        AtomicReference<Path> responseSeen = new AtomicReference<>();
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(call -> {
            responseSeen.set(responsePath(call.getArgument(0)));
            assertThat(Files.getPosixFilePermissions(responseSeen.get())).isEqualTo(EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            assertThat(Files.readString(responseSeen.get())).contains("/TargetPassword:");
            return new ProcessRunner.Result(0, "imported", "");
        });
        SqlServerBacpacRestoreAdapter adapter = restore(true);

        adapter.restore(CONNECTION, "source_shop", artifact);

        ArgumentCaptor<List<String>> command = commandCaptor();
        verify(runner).run(command.capture(), anyMap(), eq(JOB_TIMEOUT));
        assertThat(command.getValue()).contains(
                "/Action:Import", "/SourceFile:" + artifact,
                "/TargetServerName:tcp:sql.internal,1433", "/TargetDatabaseName:shop",
                "/TargetUser:backup", "/TargetEncryptConnection:True",
                "/TargetTrustServerCertificate:true", "/TargetTimeout:7",
                "/p:CommandTimeout=0", "/p:LongRunningCommandTimeout=0");
        assertThat(command.getValue()).noneMatch(value -> value.contains(PASSWORD));
        assertThat(responseSeen.get()).doesNotExist();
        assertThat(temporaryDirectory.resolve("scratch")).isEmptyDirectory();
    }

    @Test
    void failedImportExplainsThatTheDestinationMustBeEmpty() throws Exception {
        Path artifact = temporaryDirectory.resolve("shop.bacpac");
        Files.writeString(artifact, "bacpac");
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "database contains user objects"));

        assertThatThrownBy(() -> restore(false).restore(CONNECTION, "shop", artifact))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("must be missing or contain no user-defined objects")
                .hasMessageContaining("database contains user objects");
    }

    @Test
    void missingArtifactNeverStartsSqlPackage() {
        Path missing = temporaryDirectory.resolve("missing.bacpac");

        assertThatThrownBy(() -> restore(false).restore(CONNECTION, "shop", missing))
                .isInstanceOf(RestoreFailedException.class)
                .hasMessageContaining("missing or unreadable");
    }

    @Test
    void refusesMissingBinariesWhenThePackStarts() {
        Path missing = temporaryDirectory.resolve("missing-sqlpackage");

        assertThatThrownBy(() -> new SqlServerBacpacBackupAdapter(
                runner, missing, CONNECT_TIMEOUT, JOB_TIMEOUT, false, temporaryFiles))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.sqlserver.sqlpackage-path")
                .hasMessageContaining(missing.toString());

        assertThatThrownBy(() -> new SqlServerCliConnectionTestAdapter(
                runner, missing, CONNECT_TIMEOUT, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dbbackup.sqlserver.sqlcmd-path")
                .hasMessageContaining(missing.toString());
    }

    @Test
    void responseFileRejectsLineBreaksBeforeCreatingASecretFile() {
        assertThatThrownBy(() -> SqlPackageResponseFile.use(
                "SourcePassword", "unsafe\n/Action:Script", ignored -> null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CR, LF, or NUL");
    }

    private SqlServerBacpacBackupAdapter backup(boolean trust) {
        return new SqlServerBacpacBackupAdapter(
                runner, binary, CONNECT_TIMEOUT, JOB_TIMEOUT, trust, temporaryFiles);
    }

    private SqlServerBacpacRestoreAdapter restore(boolean trust) {
        return new SqlServerBacpacRestoreAdapter(
                runner, binary, CONNECT_TIMEOUT, JOB_TIMEOUT, trust, temporaryFiles);
    }

    private static Path responsePath(List<String> command) {
        return Path.of(command.stream()
                .filter(value -> value.startsWith("@"))
                .findFirst()
                .orElseThrow()
                .substring(1));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> environment(org.mockito.invocation.InvocationOnMock call) {
        return call.getArgument(1, Map.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<List<String>> commandCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<Map<String, String>> mapCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
    }
}
