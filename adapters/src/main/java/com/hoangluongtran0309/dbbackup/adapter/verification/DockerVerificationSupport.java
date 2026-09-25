package com.hoangluongtran0309.dbbackup.adapter.verification;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Bounded Docker CLI operations shared by the network-engine verification adapters. */
@Component
public class DockerVerificationSupport {
    private static final Duration STABLE_READY_WINDOW = Duration.ofSeconds(2);
    private final ProcessRunner runner;
    private final Duration pullTimeout;
    private final Duration startupTimeout;
    private final Duration cleanupTimeout;

    public DockerVerificationSupport(
            ProcessRunner runner,
            @Value("${dbbackup.verification.pull-timeout:10m}") Duration pullTimeout,
            @Value("${dbbackup.verification.startup-timeout:2m}") Duration startupTimeout,
            @Value("${dbbackup.verification.cleanup-timeout:30s}") Duration cleanupTimeout) {
        this.runner = runner;
        this.pullTimeout = pullTimeout;
        this.startupTimeout = startupTimeout;
        this.cleanupTimeout = cleanupTimeout;
    }

    public boolean available() {
        try {
            return runner.run(List.of("docker", "version", "--format", "{{.Server.Version}}"),
                    Map.of(), Duration.ofSeconds(10)).succeeded();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public boolean imagePresent(String image) {
        return success(List.of("docker", "image", "inspect", image), Duration.ofSeconds(15));
    }

    public void requireImage(String image) {
        if (success(List.of("docker", "image", "inspect", image), Duration.ofSeconds(15))) return;
        requireSuccess(run(List.of("docker", "pull", image), pullTimeout), "Could not pull " + image);
    }

    public String start(DatabaseEngine engine, UUID id, String image, Map<String, String> environment) {
        requireImage(image);
        String name = containerName(engine, id);
        List<String> command = new ArrayList<>(List.of(
                "docker", "run", "-d", "--rm", "--name", name,
                "--label", "dbbackup.restore-verification=" + id));
        environment.keySet().forEach(key -> {
            command.add("-e");
            command.add(key);
        });
        command.add(image);
        requireSuccess(run(command, environment, startupTimeout), "Could not start the temporary database");
        return name;
    }

    public ProcessRunner.Result exec(String container, Duration timeout, String... command) {
        return execAsUser(container, timeout, null, Map.of(), command);
    }

    public ProcessRunner.Result execAsUser(
            String container, Duration timeout, String user, String... command) {
        return execAsUser(container, timeout, user, Map.of(), command);
    }

    public ProcessRunner.Result execAsUser(
            String container, Duration timeout, String user,
            Map<String, String> environment, String... command) {
        List<String> values = new ArrayList<>(List.of("docker", "exec"));
        if (user != null && !user.isBlank()) {
            values.add("--user");
            values.add(user);
        }
        environment.keySet().forEach(key -> {
            values.add("-e");
            values.add(key);
        });
        values.add(container);
        values.addAll(List.of(command));
        return run(values, environment, timeout);
    }

    public ProcessRunner.Result execWithEnvironment(
            String container, Duration timeout, Map<String, String> environment, String... command) {
        return execAsUser(container, timeout, null, environment, command);
    }

    public ProcessRunner.Result feed(
            String container, Duration timeout, InputStream input,
            Map<String, String> environment, String... command) {
        return feedAsUser(container, timeout, input, null, environment, command);
    }

    public ProcessRunner.Result feedAsUser(
            String container, Duration timeout, InputStream input, String user,
            Map<String, String> environment, String... command) {
        List<String> values = new ArrayList<>(List.of("docker", "exec", "-i"));
        if (user != null && !user.isBlank()) {
            values.add("--user");
            values.add(user);
        }
        environment.keySet().forEach(key -> {
            values.add("-e");
            values.add(key);
        });
        values.add(container);
        values.addAll(List.of(command));
        try {
            return runner.runFeeding(values, environment, timeout, input);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    public void copy(Path source, String container, String destination, Duration timeout) {
        requireSuccess(run(List.of("docker", "cp", source.toString(), container + ":" + destination), timeout),
                "Could not copy the backup into the temporary database");
    }

    public void remove(DatabaseEngine engine, UUID id) {
        String name = containerName(engine, id);
        ProcessRunner.Result result = run(List.of("docker", "rm", "-f", name), cleanupTimeout);
        if (!result.succeeded() && !result.errorOutput().contains("No such container")) {
            throw new IllegalStateException("Could not remove temporary database: " + result.errorOutput());
        }
    }

    public boolean success(List<String> command, Duration timeout) {
        try {
            return run(command, timeout).succeeded();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void waitUntilReady(String container, Map<String, String> environment, String... command) {
        waitUntilReady(container, startupTimeout, environment, command);
    }

    public void waitUntilReady(
            String container, Duration timeout, Map<String, String> environment, String... command) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long readySince = 0;
        while (System.nanoTime() < deadline) {
            try {
                if (execWithEnvironment(container, Duration.ofSeconds(5), environment, command).succeeded()) {
                    if (readySince == 0) {
                        readySince = System.nanoTime();
                    } else if (System.nanoTime() - readySince >= STABLE_READY_WINDOW.toNanos()) {
                        return;
                    }
                } else {
                    readySince = 0;
                }
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Temporary database startup was interrupted", e);
            } catch (RuntimeException failure) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IllegalStateException("Temporary database startup was interrupted", failure);
                }
                // Official images can stop their bootstrap server before the final
                // process starts. A failed probe resets the stable-ready window.
                readySince = 0;
            }
        }
        throw new IllegalStateException("Temporary database did not become ready within " + timeout);
    }

    public static void requireSuccess(ProcessRunner.Result result, String prefix) {
        if (!result.succeeded()) {
            throw new IllegalStateException("%s (exit %d): %s"
                    .formatted(prefix, result.exitCode(), result.errorOutput()));
        }
    }

    public static String containerName(DatabaseEngine engine, UUID id) {
        return "dbbackup-verify-" + engine.name().toLowerCase(java.util.Locale.ROOT) + "-" + id;
    }

    private ProcessRunner.Result run(List<String> command, Duration timeout) {
        return run(command, Map.of(), timeout);
    }

    private ProcessRunner.Result run(
            List<String> command, Map<String, String> environment, Duration timeout) {
        try {
            return runner.run(command, environment, timeout);
        } catch (ProcessRunner.ProcessFailedException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
