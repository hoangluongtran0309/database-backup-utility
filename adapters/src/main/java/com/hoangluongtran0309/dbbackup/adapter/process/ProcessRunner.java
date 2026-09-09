package com.hoangluongtran0309.dbbackup.adapter.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
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
        CompletableFuture<String> stdout = readAsync(process.getInputStream());
        CompletableFuture<String> stderr = readAsync(process.getErrorStream());

        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new ProcessFailedException(
                        "'%s' did not finish within %ds and was killed"
                                .formatted(command.getFirst(), timeout.toSeconds()));
            }
            return new Result(process.exitValue(), stdout.join(), stderr.join());
        } catch (InterruptedException e) {
            process.destroyForcibly();
            // Restore the flag: swallowing it strands whoever is trying to shut
            // this thread down.
            Thread.currentThread().interrupt();
            throw new ProcessFailedException(
                    "Interrupted while waiting for '%s'".formatted(command.getFirst()), e);
        }
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
