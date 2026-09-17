package com.hoangluongtran0309.dbbackup.adapter.mysql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/**
 * Checks how the command line is assembled. The binary is {@code /bin/sh} —
 * this test is about arguments and environment, and needs no MySQL.
 */
@ExtendWith(MockitoExtension.class)
class MysqlClientTest {

    private static final DatabaseConnection CONNECTION =
            new DatabaseConnection(DatabaseEngine.MYSQL, "db.internal", 3307, "shop", "backup", "s3cr3t");

    @Mock
    private ProcessRunner processRunner;

    @Captor
    private ArgumentCaptor<List<String>> command;

    @Captor
    private ArgumentCaptor<Map<String, String>> environment;

    private MysqlClient client;

    @BeforeEach
    void setUp() {
        client = new MysqlClient(processRunner, Path.of("/bin/sh"), Duration.ofSeconds(10));
    }

    /**
     * The bug this rewrite exists for: given the literal string "localhost" the
     * MySQL client connects over a Unix socket and ignores --port entirely, so
     * a target on 127.0.0.1:3307 would be probed somewhere else.
     */
    @ParameterizedTest
    @ValueSource(strings = {"localhost", "LOCALHOST", "LocalHost"})
    void rewritesLocalhostToTheLoopbackAddressSoThePortIsHonoured(String host) {
        assertThat(MysqlClient.tcpHost(host)).isEqualTo("127.0.0.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "db.internal", "localhost.example.com", "10.0.0.5"})
    void leavesEveryOtherHostAlone(String host) {
        assertThat(MysqlClient.tcpHost(host)).isEqualTo(host);
    }

    @Test
    void passesThePasswordInTheEnvironmentAndNeverOnTheCommandLine() {
        when(processRunner.run(anyList(), any(), any())).thenReturn(new ProcessRunner.Result(0, "1", ""));

        client.execute(CONNECTION, "SELECT 1");

        verify(processRunner).run(command.capture(), environment.capture(), any());
        assertThat(environment.getValue()).containsEntry("MYSQL_PWD", "s3cr3t");
        // ps would show this list to every user on the host.
        assertThat(command.getValue()).noneMatch(argument -> argument.contains("s3cr3t"));
    }

    @Test
    void buildsTheExpectedCommandLine() {
        when(processRunner.run(anyList(), any(), any())).thenReturn(new ProcessRunner.Result(0, "1", ""));

        client.execute(CONNECTION, "SELECT 1");

        verify(processRunner).run(command.capture(), any(), any());
        assertThat(command.getValue()).containsExactly(
                "/bin/sh",
                "--host=db.internal",
                "--port=3307",
                "--user=backup",
                "--connect-timeout=10",
                "--batch",
                "--skip-column-names",
                "--execute=SELECT 1",
                "shop");
    }

    @Test
    void givesTheClientItsOwnTimeoutBeforeKillingIt() {
        when(processRunner.run(anyList(), any(), any())).thenReturn(new ProcessRunner.Result(0, "1", ""));
        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);

        client.execute(CONNECTION, "SELECT 1");

        verify(processRunner).run(anyList(), any(), timeout.capture());
        // Strictly longer than --connect-timeout, so the client reports its own
        // error rather than being killed mid-sentence.
        assertThat(timeout.getValue()).isGreaterThan(Duration.ofSeconds(10));
    }

    @Test
    void refusesToStartWhenTheConfiguredBinaryIsNotExecutable() {
        assertThatThrownBy(() -> new MysqlClient(
                processRunner, Path.of("/nonexistent/mysql"), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/nonexistent/mysql")
                .hasMessageContaining("not an executable file");
    }
}
