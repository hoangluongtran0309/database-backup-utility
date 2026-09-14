package com.hoangluongtran0309.dbbackup.adapter.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

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
 */
@Component
public class ProcessRunner {

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
     * Runs a command whose stdin is the contents of a file.
     *
     * <p>Redirected by the operating system rather than written from a thread
     * of ours. Feeding a child's stdin from Java means a fourth stream to keep
     * moving in lockstep with the other three, and a writer that blocks while
     * the child is blocked writing output nobody is draining yet is the same
     * deadlock this class exists to avoid — in a shape that is much harder to
     * see. Letting the kernel do it removes the possibility.
     */
    public Result runWithInput(
            List<String> command,
            Map<String, String> environment,
            Duration timeout,
            Path stdinFile) {

        return execute(command, environment, timeout, null,
                Objects.requireNonNull(stdinFile, "stdinFile"));
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
            Path stdinFile) {

        ProcessBuilder builder = new ProcessBuilder(new ArrayList<>(command));
        builder.environment().putAll(environment);
        if (stdinFile != null) {
            builder.redirectInput(stdinFile.toFile());
        }

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
                throw new ProcessFailedException(
                        "'%s' did not finish within %ds and was killed"
                                .formatted(command.getFirst(), timeout.toSeconds()));
            }
            return new Result(process.exitValue(), join(stdout, command), join(stderr, command));
        } catch (InterruptedException e) {
            process.destroyForcibly();
            // Restore the flag: swallowing it strands whoever is trying to shut
            // this thread down.
            Thread.currentThread().interrupt();
            throw new ProcessFailedException(
                    "Interrupted while waiting for '%s'".formatted(command.getFirst()), e);
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
        });
    }

    private static CompletableFuture<String> readAsync(InputStream stream) {
        return CompletableFuture.supplyAsync(() -> {
            try (stream) {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
