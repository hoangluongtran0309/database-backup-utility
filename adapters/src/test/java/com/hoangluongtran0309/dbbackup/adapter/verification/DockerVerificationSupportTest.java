package com.hoangluongtran0309.dbbackup.adapter.verification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

class DockerVerificationSupportTest {

    @Test
    void startsOnlyTheDeterministicallyNamedUnpublishedContainer() {
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(0, "", ""));
        DockerVerificationSupport docker = support(runner);
        UUID id = UUID.randomUUID();

        String name = docker.start(DatabaseEngine.MYSQL, id, "mysql:8.4", Map.of("PASSWORD", "random"));

        assertThat(name).isEqualTo("dbbackup-verify-mysql-" + id);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> commands = ArgumentCaptor.forClass(List.class);
        verify(runner, atLeastOnce()).run(commands.capture(), anyMap(), any());
        List<String> run = commands.getAllValues().stream()
                .filter(command -> command.size() > 1 && command.get(1).equals("run"))
                .findFirst().orElseThrow();
        assertThat(run).contains("--rm", "--name", name,
                "--label", "dbbackup.restore-verification=" + id, "PASSWORD=random", "mysql:8.4")
                .doesNotContain("-p", "--publish");
    }

    @Test
    void startupInterruptionIsNotRetriedAndKeepsTheInterruptFlag() {
        ProcessRunner runner = mock(ProcessRunner.class);
        when(runner.run(anyList(), anyMap(), any())).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new ProcessRunner.ProcessFailedException("Interrupted while waiting for docker");
        });
        try {
            assertThatThrownBy(() -> support(runner).waitUntilReady("container", Map.of(), "probe"))
                    .hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void cleanupFailureIsReportedButAnAlreadyAbsentContainerIsAccepted() {
        ProcessRunner missingRunner = mock(ProcessRunner.class);
        when(missingRunner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "No such container"));
        support(missingRunner).remove(DatabaseEngine.MONGODB, UUID.randomUUID());

        ProcessRunner failingRunner = mock(ProcessRunner.class);
        when(failingRunner.run(anyList(), anyMap(), any()))
                .thenReturn(new ProcessRunner.Result(1, "", "permission denied"));
        assertThatThrownBy(() -> support(failingRunner).remove(DatabaseEngine.MONGODB, UUID.randomUUID()))
                .hasMessageContaining("permission denied");
    }

    private static DockerVerificationSupport support(ProcessRunner runner) {
        return new DockerVerificationSupport(runner, Duration.ofMinutes(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1));
    }
}
