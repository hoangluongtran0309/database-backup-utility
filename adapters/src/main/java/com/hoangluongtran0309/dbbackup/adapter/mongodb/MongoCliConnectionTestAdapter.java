package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

/** Probes MongoDB through mongodump without reading an operator collection. */
@Component
class MongoCliConnectionTestAdapter implements ConnectionTestPort {

    private final ProcessRunner processRunner;
    private final Path binary;
    private final Duration timeout;

    MongoCliConnectionTestAdapter(
            ProcessRunner processRunner,
            @Value("${dbbackup.mongodb.dump-path}") Path binary,
            @Value("${dbbackup.mongodb.connect-timeout}") Duration timeout) {
        this.processRunner = processRunner;
        this.binary = MongoBinaries.require(binary, "dbbackup.mongodb.dump-path");
        this.timeout = timeout;
    }

    @Override
    public DatabaseEngine engine() {
        return DatabaseEngine.MONGODB;
    }

    @Override
    public Result test(DatabaseConnection connection) {
        try {
            ProcessRunner.Result result = MongoCredentialFile.use(connection.password(), config ->
                    processRunner.run(command(connection, config), Map.of(), timeout));
            return result.succeeded() ? Result.ok() : Result.failed(result.errorOutput());
        } catch (ProcessRunner.ProcessFailedException | MongoCredentialFile.CredentialFileException e) {
            return Result.failed(e.getMessage());
        }
    }

    List<String> command(DatabaseConnection connection, Path config) {
        List<String> command = new ArrayList<>(MongoCommand.connection(binary, connection, config));
        command.add("--db=" + connection.database());
        command.add("--collection=__dbbackup_connection_probe_"
                + UUID.randomUUID().toString().replace("-", ""));
        // No value means stdout. An absent collection produces only a small
        // archive header while still exercising connection and authentication.
        command.add("--archive");
        return List.copyOf(command);
    }
}
