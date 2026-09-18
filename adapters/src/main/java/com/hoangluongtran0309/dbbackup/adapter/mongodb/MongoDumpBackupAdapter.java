package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;

/** Produces a gzip-compressed MongoDB archive directly on local storage. */
@Component
class MongoDumpBackupAdapter implements LogicalBackupPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration timeout;

    MongoDumpBackupAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mongodb.dump-path}") Path binary,
            @Value("${dbbackup.backup.timeout}") Duration timeout) {
        this.processRunner = processRunner;
        this.binary = MongoBinaries.require(binary, "dbbackup.mongodb.dump-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MONGODB;
    }

    @Override
    public String artifactSuffix() {
        return ".archive.gz";
    }

    @Override
    public long dumpTo(DatabaseConnection connection, Path destination) {
        try {
            ProcessRunner.Result result = MongoCredentialFile.use(connection.password(), config ->
                    processRunner.run(command(connection, destination, config), Map.of(), timeout));
            if (!result.succeeded()) {
                throw failed(destination,
                        "mongodump exited with %d: %s".formatted(result.exitCode(), result.errorOutput()));
            }
            return Files.size(destination);
        } catch (ProcessRunner.ProcessFailedException | MongoCredentialFile.CredentialFileException e) {
            throw failed(destination, e.getMessage());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    List<String> command(DatabaseConnection connection, Path destination, Path config) {
        List<String> command = new ArrayList<>(MongoCommand.connection(binary, connection, config));
        command.add("--db=" + connection.database());
        command.add("--gzip");
        command.add("--archive=" + destination);
        return List.copyOf(command);
    }

    private static BackupFailedException failed(Path destination, String message) {
        try {
            Files.deleteIfExists(destination);
        } catch (IOException e) {
            return new BackupFailedException(
                    "%s (and the partial file '%s' could not be removed: %s)"
                            .formatted(message, destination, e.getMessage()));
        }
        return new BackupFailedException(message);
    }
}
