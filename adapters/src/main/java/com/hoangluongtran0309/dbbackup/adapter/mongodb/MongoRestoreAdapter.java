package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.LogicalRestorePort;

/** Validates and restores one compressed MongoDB archive. */
@Component
class MongoRestoreAdapter implements LogicalRestorePort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration timeout;

    MongoRestoreAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mongodb.restore-path}") Path binary,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
        this.processRunner = processRunner;
        this.binary = MongoBinaries.require(binary, "dbbackup.mongodb.restore-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MONGODB;
    }

    @Override
    public void restore(DatabaseConnection connection, String sourceDatabase, Path artifact) {
        if (!Files.isReadable(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is missing or unreadable".formatted(artifact));
        }
        run(command(connection, sourceDatabase, artifact, true), connection,
                "The MongoDB archive is not readable");
        run(command(connection, sourceDatabase, artifact, false), connection, "mongorestore failed");
    }

    private void run(Command command, DatabaseConnection connection, String prefix) {
        ProcessRunner.Result result;
        try {
            result = MongoCredentialFile.use(connection.password(), config ->
                    processRunner.run(command.withConfig(config), Map.of(), timeout));
        } catch (ProcessRunner.ProcessFailedException | MongoCredentialFile.CredentialFileException e) {
            throw new RestoreFailedException(e.getMessage(), e);
        }
        if (!result.succeeded()) {
            throw new RestoreFailedException(
                    "%s (exit %d): %s".formatted(prefix, result.exitCode(), result.errorOutput()));
        }
    }

    Command command(DatabaseConnection connection, String sourceDatabase, Path artifact, boolean dryRun) {
        return new Command(connection, sourceDatabase, artifact, dryRun);
    }

    final class Command {
        private final DatabaseConnection connection;
        private final String sourceDatabase;
        private final Path artifact;
        private final boolean dryRun;

        private Command(
                DatabaseConnection connection, String sourceDatabase, Path artifact, boolean dryRun) {
            this.connection = connection;
            this.sourceDatabase = sourceDatabase;
            this.artifact = artifact;
            this.dryRun = dryRun;
        }

        List<String> withConfig(Path config) {
            List<String> command = new ArrayList<>(MongoCommand.connection(binary, connection, config));
            command.add("--archive=" + artifact);
            command.add("--gzip");
            command.add("--nsInclude=" + sourceDatabase + ".*");
            command.add("--nsFrom=" + sourceDatabase + ".*");
            command.add("--nsTo=" + connection.database() + ".*");
            if (dryRun) {
                command.add("--dryRun");
            } else {
                command.add("--drop");
                command.add("--stopOnError");
            }
            return List.copyOf(command);
        }
    }
}
