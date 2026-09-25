package com.hoangluongtran0309.dbbackup.adapter.oracle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;

class OracleRestoreVerificationAdapterTest {

    @Test
    void remapsTheSourceSchemaChecksTablesAndAlwaysRemovesTheContainer() {
        DockerVerificationSupport docker = mock(DockerVerificationSupport.class);
        UUID id = UUID.randomUUID();
        when(docker.start(any(), any(), anyString(), anyMap())).thenReturn("oracle-container");
        when(docker.execAsUser(anyString(), any(Duration.class), anyString(), any(String[].class)))
                .thenReturn(success());
        when(docker.feed(anyString(), any(Duration.class), any(InputStream.class),
                anyMap(), any(String[].class)))
                .thenReturn(success(), success(), success(),
                        new ProcessRunner.Result(0, "TABLE_COUNT=3\nINVALID_COUNT=0\n", ""));
        OracleRestoreVerificationAdapter adapter = adapter(docker);

        RestoreVerificationResult result = adapter.verify(id, target(), Path.of("/staging/orders.dmp"));

        assertThat(result.checkedObjects()).isEqualTo(3);
        assertThat(result.summary()).contains("3 Oracle table(s)").contains("no invalid user objects");
        verify(docker).remove(DatabaseEngine.ORACLE, id);
    }

    @Test
    void invalidObjectsFailTheAttemptAndStillCleanUp() {
        DockerVerificationSupport docker = mock(DockerVerificationSupport.class);
        UUID id = UUID.randomUUID();
        when(docker.start(any(), any(), anyString(), anyMap())).thenReturn("oracle-container");
        when(docker.execAsUser(anyString(), any(Duration.class), anyString(), any(String[].class)))
                .thenReturn(success());
        when(docker.feed(anyString(), any(Duration.class), any(InputStream.class),
                anyMap(), any(String[].class)))
                .thenReturn(success(), success(), success(),
                        new ProcessRunner.Result(0, "TABLE_COUNT=0\nINVALID_COUNT=2\n", ""));

        assertThatThrownBy(() -> adapter(docker).verify(id, target(), Path.of("/staging/orders.dmp")))
                .hasMessageContaining("2 invalid user object");
        verify(docker).remove(DatabaseEngine.ORACLE, id);
    }

    private static OracleRestoreVerificationAdapter adapter(DockerVerificationSupport docker) {
        return new OracleRestoreVerificationAdapter(
                docker, true, "oracle:test", Duration.ofMinutes(1), Duration.ofMinutes(2));
    }

    private static DatabaseTarget target() {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name("oracle")
                .engine(DatabaseEngine.ORACLE).host("source.internal").port(1521)
                .databaseName("SOURCEPDB").username("APP_OWNER").dataPumpDirectory("PUMP")
                .passwordCiphertext("source-secret-must-not-be-used").createdAt(Instant.EPOCH).build();
    }

    private static ProcessRunner.Result success() {
        return new ProcessRunner.Result(0, "", "");
    }
}
