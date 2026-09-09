package com.hoangluongtran0309.dbbackup.adapter.process;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import org.junit.jupiter.api.io.TempDir;

/**
 * Uses real child processes — {@code /bin/sh} and friends are as much a part of
 * this class's contract as its Java is, and a mocked ProcessBuilder would test
 * none of the three failure modes that matter.
 */
class ProcessRunnerTest {

    private final ProcessRunner runner = new ProcessRunner();

    @Test
    void capturesStdoutAndExitCode() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "echo hello"), Map.of(), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isZero();
        assertThat(result.succeeded()).isTrue();
        assertThat(result.stdout()).isEqualTo("hello\n");
        assertThat(result.stderr()).isEmpty();
    }

    @Test
    void capturesStderrAndANonZeroExitCode() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "echo boom >&2; exit 3"), Map.of(), Duration.ofSeconds(5));

        assertThat(result.exitCode()).isEqualTo(3);
        assertThat(result.succeeded()).isFalse();
        assertThat(result.errorOutput()).isEqualTo("boom");
    }

    /**
     * The regression this class exists for. A pipe buffer is around 64 KiB; a
     * runner that reads stdout to EOF before touching stderr hangs forever once
     * the child fills the other buffer. Both streams here are far larger than
     * one buffer, so a sequential reader cannot pass this test.
     *
     * <p>{@code SEPARATE_THREAD} is not decoration. A plain {@code @Timeout}
     * only checks the clock after the method returns, so a genuinely deadlocked
     * runner would hang the build forever instead of failing it — verified by
     * reintroducing the sequential read and watching the build hang rather than
     * go red. Running the body on another thread lets JUnit abandon it.
     */
    @Test
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    void doesNotDeadlockWhenBothStreamsExceedThePipeBuffer() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c",
                        // 2>/dev/null silences the "Broken pipe" that `yes`
                        // reports when `head` closes the pipe on it; without it
                        // the byte counts below are not exact.
                        "yes out 2>/dev/null | head -c 500000; "
                                + "yes err 2>/dev/null | head -c 500000 >&2"),
                Map.of(),
                Duration.ofSeconds(25));

        assertThat(result.stdout()).hasSize(500_000);
        assertThat(result.stderr()).hasSize(500_000);
        assertThat(result.succeeded()).isTrue();
    }

    @Test
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    void killsAChildThatOutlivesItsTimeout() {
        assertThatThrownBy(() -> runner.run(
                List.of("/bin/sh", "-c", "sleep 60"), Map.of(), Duration.ofMillis(500)))
                .isInstanceOf(ProcessRunner.ProcessFailedException.class)
                .hasMessageContaining("did not finish");
    }

    @Test
    void passesEnvironmentVariablesToTheChildOnly() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "echo \"$MYSQL_PWD\""),
                Map.of("MYSQL_PWD", "s3cr3t"),
                Duration.ofSeconds(5));

        assertThat(result.stdout()).isEqualTo("s3cr3t\n");
        // Nothing was leaked into this JVM, which is what makes concurrent jobs
        // with different credentials safe.
        assertThat(System.getProperty("MYSQL_PWD")).isNull();
        assertThat(System.getenv("MYSQL_PWD")).isNull();
    }

    @Test
    void namesTheBinaryWhenItCannotBeStarted() {
        assertThatThrownBy(() -> runner.run(
                List.of("/nonexistent/mysql", "--version"), Map.of(), Duration.ofSeconds(5)))
                .isInstanceOf(ProcessRunner.ProcessFailedException.class)
                .hasMessageContaining("/nonexistent/mysql");
    }

    @Test
    void argumentsAreNotInterpretedByAShell() {
        // If this went through a shell, the semicolon would start a new command.
        ProcessRunner.Result result = runner.run(
                List.of("/bin/echo", "a; rm -rf /", "b"), Map.of(), Duration.ofSeconds(5));

        assertThat(result.stdout()).isEqualTo("a; rm -rf / b\n");
    }

    // --- streaming stdout ---------------------------------------------------

    @Test
    void streamsStdoutIntoTheSinkInsteadOfCapturingIt() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ProcessRunner.Result result = runner.runStreaming(
                List.of("/bin/sh", "-c", "echo hello"), Map.of(), Duration.ofSeconds(5), sink);

        assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("hello\n");
        assertThat(result.stdout()).isEmpty();
        assertThat(result.succeeded()).isTrue();
    }

    /**
     * Ten megabytes is far more than a pipe buffer and far more than anything
     * that should sit in a String. A truncated copy here would be a silently
     * corrupt backup.
     */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    void streamsOutputFarLargerThanMemoryWouldWantWithoutTruncating() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ProcessRunner.Result result = runner.runStreaming(
                List.of("/bin/sh", "-c", "yes payload 2>/dev/null | head -c 10000000"),
                Map.of(), Duration.ofSeconds(50), sink);

        assertThat(sink.size()).isEqualTo(10_000_000);
        assertThat(result.succeeded()).isTrue();
    }

    /** stderr still has to be drained while stdout is being streamed. */
    @Test
    @Timeout(value = 60, threadMode = ThreadMode.SEPARATE_THREAD)
    void doesNotDeadlockWhenStreamingStdoutAndStderrIsAlsoLarge() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ProcessRunner.Result result = runner.runStreaming(
                List.of("/bin/sh", "-c",
                        "yes out 2>/dev/null | head -c 500000; "
                                + "yes err 2>/dev/null | head -c 500000 >&2"),
                Map.of(), Duration.ofSeconds(50), sink);

        assertThat(sink.size()).isEqualTo(500_000);
        assertThat(result.stderr()).hasSize(500_000);
    }

    @Test
    void streamingStillReportsANonZeroExitAndItsStderr() {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();

        ProcessRunner.Result result = runner.runStreaming(
                List.of("/bin/sh", "-c", "echo partial; echo boom >&2; exit 4"),
                Map.of(), Duration.ofSeconds(5), sink);

        assertThat(result.exitCode()).isEqualTo(4);
        assertThat(result.errorOutput()).isEqualTo("boom");
        assertThat(sink.toString(StandardCharsets.UTF_8)).isEqualTo("partial\n");
    }

    /**
     * A full disk during a backup. Without killing the child it would block on
     * a pipe nobody is draining until the timeout — half an hour, by default.
     */
    @Test
    @Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
    void killsTheChildAtOnceWhenTheSinkCannotBeWrittenTo() {
        OutputStream broken = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("No space left on device");
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                throw new IOException("No space left on device");
            }
        };

        assertThatThrownBy(() -> runner.runStreaming(
                List.of("/bin/sh", "-c", "yes 2>/dev/null | head -c 5000000; sleep 60"),
                Map.of(), Duration.ofSeconds(25), broken))
                .isInstanceOf(ProcessRunner.ProcessFailedException.class)
                .hasMessageContaining("No space left on device");
    }

    @Test
    void doesNotCloseTheSinkSoAGzipTrailerIsTheCallersToWrite(@TempDir Path dir) throws Exception {
        Path archive = dir.resolve("out.gz");

        try (OutputStream gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
            runner.runStreaming(
                    List.of("/bin/sh", "-c", "echo compressed payload"),
                    Map.of(), Duration.ofSeconds(5), gzip);
            // Still open here: had runStreaming closed it, the close above
            // would throw and the archive would have no trailer.
        }

        try (var in = new GZIPInputStream(Files.newInputStream(archive))) {
            assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("compressed payload\n");
        }
    }

    // --- result shape -------------------------------------------------------

    @Test
    void errorOutputFallsBackToStdoutWhenStderrIsEmpty() {
        ProcessRunner.Result result = runner.run(
                List.of("/bin/sh", "-c", "echo said-on-stdout; exit 1"), Map.of(), Duration.ofSeconds(5));

        assertThat(result.errorOutput()).isEqualTo("said-on-stdout");
    }
}
