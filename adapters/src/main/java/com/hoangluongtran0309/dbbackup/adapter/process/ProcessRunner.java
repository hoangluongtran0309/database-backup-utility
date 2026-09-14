package com.hoangluongtran0309.dbbackup.adapter.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.springframework.stereotype.Component;

/**
 * Runs an external command and collects what it said.
 *
 * <p>Every external tool this application drives goes through here, because
 * three things about child processes are easy to get wrong and expensive to
 * debug, and each is fixed once in this class:
 *
 * <ol>
 *   <li><b>Both pipes are drained concurrently, before {@code waitFor}.</b>
 *       A pipe holds only a few kilobytes. Reading stdout to EOF and only then
 *       reading stderr deadlocks the moment the child fills the stderr buffer:
 *       it blocks writing, we block reading, and neither side moves again.</li>
 *   <li><b>There is a timeout.</b> A plain {@code waitFor()} against a hung
 *       server blocks its thread for the life of the JVM.</li>
 *   <li><b>Secrets travel in the environment.</b> {@code environment()} is
 *       per-process; {@code System.setProperty} is JVM-global and would leak
 *       between jobs running at the same time.</li>
 * </ol>
 *
 * <p>Every pipe gets a thread of its own, started for it and gone when it is
 * done, never one borrowed from the common pool. Each blocks for as long as
 * its child runs, and the common pool has one worker fewer than the machine
 * has cores: two jobs at three pipes each would leave a pipe on a small host
 * waiting for a worker that never frees up, and its child blocked on it until
 * the timeout.
 */
@Component
public class ProcessRunner {

    private static final int BUFFER_SIZE = 64 * 1024;

    private static final ExecutorService PIPE_THREADS = Executors.newThreadPerTaskExecutor(
            // Daemon: a pipe of a child still running at shutdown must not
            // hold the JVM up, any more than the job thread waiting on it does.
            Thread.ofPlatform().daemon().name("process-pipe-", 0).factory());

    /**
     * @param exitCode the child's exit status, 0 conventionally meaning success
     */
    public record Result(int exitCode, String stdout, String stderr) {

        public boolean succeeded() {
            return exitCode == 0;
        }

        /**
         * What went wrong, preferring stderr but falling back to stdout: some
         * MySQL client errors arrive on stdout, and an empty box in the console
         * helps nobody.
         */
        public String errorOutput() {
            if (!stderr.isBlank()) {
                return stderr.strip();
            }
            return stdout.strip();
        }
    }

    /** The command could not be started, or did not finish in time. */
    public static class ProcessFailedException extends RuntimeException {
        public ProcessFailedException(String message) {
            super(message);
        }

        public ProcessFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * @param command the executable and its arguments, passed straight to
     *                {@link ProcessBuilder} — there is no shell involved, so
     *                no argument needs quoting or escaping
     * @param environment extra environment variables for the child only; this
     *                    is where credentials belong
     * @throws ProcessFailedException if the command cannot be started or
     *                                outlives {@code timeout}
     */
    public Result run(List<String> command, Map<String, String> environment, Duration timeout) {
        return execute(command, environment, timeout, null, null);
    }

    /**
     * Runs a command whose stdin is too large to hold in memory, copying
     * {@code stdinSource} into it and closing the child's stdin at the end.
     *
     * <p>The copy runs on a thread of its own, beside the two that drain
     * stdout and stderr. None of the three waits on another, so a child that
     * writes while it reads — a client echoing progress, or an error — cannot
     * block us while we block on it (ADR-016).
     *
     * <p>If <strong>reading the source</strong> fails, the child is killed
     * before its stdin is closed. Closing first would hand it a clean end of
     * input, and a client applying a dump would take half of one for all of it.
     *
     * <p>If <strong>writing to the child</strong> fails, it has stopped reading
     * — it exited, typically on an error of its own — and its exit code and
     * stderr, returned as usual, say why.
     *
     * <p>The source is <strong>not closed</strong>: the caller opened it and
     * owns it, as with {@link #runStreaming}'s sink.
     *
     * @throws ProcessFailedException also if the source could not be read
     */
    public Result runFeeding(
            List<String> command,
            Map<String, String> environment,
            Duration timeout,
            InputStream stdinSource) {

        return execute(command, environment, timeout, null,
                Objects.requireNonNull(stdinSource, "stdinSource"));
    }

    /**
     * Runs a command whose stdout is too large to hold in memory, copying it to
     * {@code stdoutSink} as it arrives.
     *
     * <p>The sink is flushed but <strong>not closed</strong>: the caller opened
     * it and owns it. That matters for a {@code GZIPOutputStream}, whose
     * trailer is only written on close, so the caller's try-with-resources is
     * what makes the file valid.
     *
     * <p>If writing to the sink fails — a full disk, most plausibly — the child
     * is killed at once rather than being left to block on a pipe nobody is
     * draining until the timeout expires.
     *
     * @return a result whose {@code stdout} is empty; it went to the sink
     */
    public Result runStreaming(
            List<String> command,
            Map<String, String> environment,
            Duration timeout,
            OutputStream stdoutSink) {

        return execute(command, environment, timeout,
                Objects.requireNonNull(stdoutSink, "stdoutSink"), null);
    }

    private Result execute(
            List<String> command,
            Map<String, String> environment,
            Duration timeout,
            OutputStream stdoutSink,
            InputStream stdinSource) {

        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.environment().putAll(environment);

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            // Typically a missing or non-executable binary. Naming it beats a
            // bare "Cannot run program".
            throw new ProcessFailedException(
                    "Could not start '%s': %s".formatted(command.getFirst(), e.getMessage()), e);
        }

        // Started before waitFor, deliberately. See the class javadoc.
        CompletableFuture<String> stdout = stdoutSink == null
                ? readAsync(process.getInputStream())
                : copyAsync(process.getInputStream(), stdoutSink);
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());
        CompletableFuture<Void> stdin = stdinSource == null
                ? CompletableFuture.completedFuture(null)
                : feedAsync(stdinSource, process);

        // A sink that cannot be written to would otherwise leave the child
        // blocked on a full pipe until the timeout, minutes or hours later.
        stdout.whenComplete((ignored, failure) -> {
            if (failure != null) {
                process.destroyForcibly();
            }
        });

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                settle(stdin);
                throw new ProcessFailedException(
                        "'%s' did not finish within %ds and was killed"
                                .formatted(command.getFirst(), timeout.toSeconds()));
            }
            // The feeder first: if the source failed, that — not the exit
            // status of a child we killed for it — is what went wrong.
            joinFeeder(stdin, command);
            return new Result(process.exitValue(), join(stdout, command), join(stderr, command));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            settle(stdin);
            // Restore the flag: swallowing it strands whoever is trying to shut
            // this thread down.
            Thread.currentThread().interrupt();
            throw new ProcessFailedException(
                    "Interrupted while waiting for '%s'".formatted(command.getFirst()), e);
        }
    }

    /**
     * Copies the source into the child's stdin, then closes it. See
     * {@link #runFeeding} for what each kind of failure means.
     */
    private static CompletableFuture<Void> feedAsync(InputStream source, Process process) {
        return CompletableFuture.runAsync(() -> {
            OutputStream stdin = process.getOutputStream();
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                int read;
                while ((read = readSource(source, buffer, process)) >= 0) {
                    try {
                        stdin.write(buffer, 0, read);
                    } catch (IOException childStoppedReading) {
                        return;
                    }
                }
            } finally {
                closeQuietly(stdin);
            }
        }, PIPE_THREADS);
    }

    /** Kills the child — and waits until it is gone — before its stdin can be closed. */
    private static int readSource(InputStream source, byte[] buffer, Process process) {
        try {
            return source.read(buffer);
        } catch (IOException e) {
            process.destroyForcibly();
            try {
                process.waitFor(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            throw new UncheckedIOException(e);
        }
    }

    private static void joinFeeder(CompletableFuture<Void> feeder, List<String> command) {
        try {
            feeder.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ProcessFailedException(
                    "Failed while reading the input for '%s': %s"
                            .formatted(command.getFirst(), cause.getMessage()), cause);
        }
    }

    /**
     * Waits for a feeder that is about to stop — its child is dead, so its
     * next write fails — without caring how it ends. The caller closes the
     * source next, and must not do so under a thread still reading it.
     */
    private static void settle(CompletableFuture<Void> feeder) {
        try {
            feeder.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException ignored) {
            // The outcome that matters is already being reported.
        }
    }

    private static void closeQuietly(OutputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // A child that has gone cannot be told the input ended; it knows.
        }
    }

    /**
     * Turns a failure inside a drain thread into this class's own exception,
     * rather than letting an {@link UncheckedIOException} escape from a
     * {@link CompletionException} the caller never asked about.
     */
    private static String join(CompletableFuture<String> future, List<String> command) {
        try {
            return future.join();
        } catch (CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new ProcessFailedException(
                    "Failed while reading the output of '%s': %s"
                            .formatted(command.getFirst(), cause.getMessage()), cause);
        }
    }

    private static CompletableFuture<String> copyAsync(InputStream stream, OutputStream sink) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                stream.transferTo(sink);
                sink.flush();
                return "";
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, PIPE_THREADS);
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, PIPE_THREADS);
    }
}
