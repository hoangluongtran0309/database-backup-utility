package com.hoangluongtran0309.dbbackup.adapter.sqlite;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationPort;

@Component
class SqliteRestoreVerificationAdapter implements RestoreVerificationPort {
    private final ProcessRunner runner;
    private final Path binary;
    private final Path root;
    private final Duration timeout;
    private final boolean enabled;

    SqliteRestoreVerificationAdapter(ProcessRunner runner,
            @Value("${dbbackup.sqlite.client-path}") Path binary,
            @Value("${dbbackup.sqlite.root}") Path root,
            @Value("${dbbackup.restore.timeout}") Duration timeout,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled) {
        this.runner = runner;
        this.binary = SqliteBinaries.require(binary, "dbbackup.sqlite.client-path");
        this.root = root.toAbsolutePath().normalize();
        this.timeout = timeout;
        this.enabled = enabled;
    }

    @Override public DatabaseEngine engine() { return DatabaseEngine.SQLITE; }
    @Override public boolean isAvailable() { return enabled; }

    @Override
    public RestoreVerificationResult verify(UUID id, DatabaseTarget source, Path artifact) {
        Path directory = directory(id);
        Path database = directory.resolve("restored.db");
        RuntimeException failure = null;
        try {
            createDirectory(directory);
            try (InputStream sql = new GZIPInputStream(Files.newInputStream(artifact))) {
                requireSuccess(runner.runFeeding(
                        java.util.List.of(binary.toString(), "-bail", database.toString()),
                        Map.of(), timeout, sql), "The SQLite backup SQL could not be loaded");
            } catch (IOException e) {
                throw new IllegalStateException("The backup is not a readable gzip archive: " + e.getMessage(), e);
            }
            ProcessRunner.Result integrity = runner.run(java.util.List.of(
                    binary.toString(), "-readonly", database.toString(), "PRAGMA integrity_check;"),
                    Map.of(), timeout);
            requireSuccess(integrity, "SQLite integrity_check failed");
            if (!integrity.stderr().isBlank() || !integrity.stdout().strip().equals("ok")) {
                throw new IllegalStateException("SQLite integrity_check did not return ok: "
                        + integrity.stdout().strip());
            }
            ProcessRunner.Result count = runner.run(java.util.List.of(binary.toString(), "-readonly", database.toString(),
                    "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%';"),
                    Map.of(), timeout);
            requireSuccess(count, "Could not inspect restored SQLite tables");
            int checked = Integer.parseInt(count.stdout().strip());
            return new RestoreVerificationResult(checked,
                    "Restored successfully and integrity_check passed for %d SQLite table(s)".formatted(checked));
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try { delete(directory); }
            catch (RuntimeException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    @Override public void abortInterrupted(UUID id) { delete(directory(id)); }

    private Path directory(UUID id) { return root.resolve(".dbbackup-verify-" + id); }

    private static void createDirectory(Path directory) {
        try {
            Files.createDirectories(directory.getParent());
            try {
                Files.createDirectory(directory,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (UnsupportedOperationException e) {
                Files.createDirectory(directory);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not create the temporary SQLite database: " + e.getMessage(), e);
        }
    }

    private static void delete(Path directory) {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException e) {
            throw new IllegalStateException("Could not remove the temporary SQLite database: " + e.getMessage(), e);
        }
    }

    private static void requireSuccess(ProcessRunner.Result result, String prefix) {
        if (!result.succeeded()) {
            throw new IllegalStateException(prefix + " (exit " + result.exitCode() + "): " + result.errorOutput());
        }
    }
}
