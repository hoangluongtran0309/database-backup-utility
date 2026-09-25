package com.hoangluongtran0309.dbbackup.adapter.sqlserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;

class SqlServerRestoreVerificationAdapterTest {

    @Test
    void requiresTheOperatorBuiltImageBeforeAcceptingManualWork() {
        DockerVerificationSupport docker = mock(DockerVerificationSupport.class);
        when(docker.available()).thenReturn(true);
        when(docker.imagePresent("sqlserver:test")).thenReturn(false);

        assertThat(adapter(docker).isAvailable()).isFalse();
    }

    @Test
    void importsChecksEveryTableAndCleansTheDisposableServer() {
        DockerVerificationSupport docker = preparedDocker();
        UUID id = UUID.randomUUID();

        RestoreVerificationResult result = adapter(docker).verify(
                id, target(), Path.of("/staging/orders.bacpac"));

        assertThat(result.checkedObjects()).isEqualTo(4);
        assertThat(result.summary()).contains("DBCC CHECKDB passed").contains("4 SQL Server table(s)");
        verify(docker).remove(DatabaseEngine.SQLSERVER, id);
    }

    @Test
    void cleanupFailurePreventsSuccess() {
        DockerVerificationSupport docker = preparedDocker();
        UUID id = UUID.randomUUID();
        doThrow(new IllegalStateException("cleanup denied"))
                .when(docker).remove(DatabaseEngine.SQLSERVER, id);

        assertThatThrownBy(() -> adapter(docker).verify(
                id, target(), Path.of("/staging/orders.bacpac")))
                .hasMessageContaining("cleanup denied");
    }

    private static DockerVerificationSupport preparedDocker() {
        DockerVerificationSupport docker = mock(DockerVerificationSupport.class);
        when(docker.start(any(), any(), anyString(), anyMap())).thenReturn("sqlserver-container");
        when(docker.execAsUser(anyString(), any(Duration.class), anyString(), any(String[].class)))
                .thenReturn(success());
        when(docker.feedAsUser(anyString(), any(Duration.class), any(InputStream.class),
                anyString(), anyMap(), any(String[].class))).thenReturn(success());
        when(docker.execAsUser(anyString(), any(Duration.class), anyString(), anyMap(),
                any(String[].class))).thenAnswer(invocation -> {
                    boolean healthCheck = java.util.Arrays.stream(invocation.getArguments())
                            .anyMatch("/opt/mssql-tools18/bin/sqlcmd"::equals);
                    return healthCheck
                            ? new ProcessRunner.Result(0, "TABLE_COUNT=4\n", "")
                            : success();
                });
        return docker;
    }

    private static SqlServerRestoreVerificationAdapter adapter(DockerVerificationSupport docker) {
        return new SqlServerRestoreVerificationAdapter(
                docker, true, "sqlserver:test", Duration.ofMinutes(1), Duration.ofMinutes(2));
    }

    private static DatabaseTarget target() {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name("sqlserver")
                .engine(DatabaseEngine.SQLSERVER).host("source.internal").port(1433)
                .databaseName("orders").username("backup")
                .passwordCiphertext("source-secret-must-not-be-used").createdAt(Instant.EPOCH).build();
    }

    private static ProcessRunner.Result success() {
        return new ProcessRunner.Result(0, "", "");
    }
}
