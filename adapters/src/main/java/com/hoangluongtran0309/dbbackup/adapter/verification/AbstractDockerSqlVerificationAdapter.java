package com.hoangluongtran0309.dbbackup.adapter.verification;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationPort;

/** Shared isolated restore for MySQL-family gzipped logical dumps. */
public abstract class AbstractDockerSqlVerificationAdapter implements RestoreVerificationPort {
    private static final String DATABASE = "verification";
    private final DockerVerificationSupport docker;
    private final boolean enabled;
    private final String image;
    private final String serverPasswordVariable;
    private final String client;
    private final Duration restoreTimeout;

    protected AbstractDockerSqlVerificationAdapter(
            DockerVerificationSupport docker, boolean enabled, String image,
            String serverPasswordVariable, String client, Duration restoreTimeout) {
        this.docker = docker;
        this.enabled = enabled;
        this.image = image;
        this.serverPasswordVariable = serverPasswordVariable;
        this.client = client;
        this.restoreTimeout = restoreTimeout;
    }

    @Override
    public boolean isAvailable() {
        return enabled && docker.available();
    }

    @Override
    public RestoreVerificationResult verify(UUID id, DatabaseTarget source, Path artifact) {
        String password = UUID.randomUUID().toString();
        String container = null;
        RuntimeException failure = null;
        try {
            container = docker.start(engine(), id, image, Map.of(serverPasswordVariable, password));
            Map<String, String> clientEnvironment = Map.of("MYSQL_PWD", password);
            docker.waitUntilReady(container, clientEnvironment,
                    client, "--protocol=TCP", "-h127.0.0.1", "-uroot", "-e", "SELECT 1");
            DockerVerificationSupport.requireSuccess(docker.execWithEnvironment(container, Duration.ofSeconds(30),
                    clientEnvironment, client, "--protocol=TCP", "-h127.0.0.1", "-uroot", "-e",
                    "CREATE DATABASE `" + DATABASE + "`"), "Could not create verification database");
            try (InputStream sql = new GZIPInputStream(Files.newInputStream(artifact))) {
                DockerVerificationSupport.requireSuccess(docker.feed(container, restoreTimeout, sql,
                        clientEnvironment, client, "--protocol=TCP", "-h127.0.0.1", "-uroot", "--batch", DATABASE),
                        client + " restore failed");
            } catch (IOException e) {
                throw new IllegalStateException("The backup is not a readable gzip archive: " + e.getMessage(), e);
            }
            int checked = checkTables(container, clientEnvironment);
            return new RestoreVerificationResult(checked,
                    "Restored successfully and CHECK TABLE passed for %d table(s)".formatted(checked));
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                docker.remove(engine(), id);
            } catch (RuntimeException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
        }
    }

    @Override
    public void abortInterrupted(UUID verificationId) {
        docker.remove(engine(), verificationId);
    }

    private int checkTables(String container, Map<String, String> environment) {
        ProcessRunner.Result listed = docker.execWithEnvironment(container, Duration.ofSeconds(30), environment,
                client, "--protocol=TCP", "-h127.0.0.1", "-uroot", "--batch", "--skip-column-names", "-e",
                "SELECT HEX(TABLE_NAME) FROM information_schema.tables WHERE table_schema='" + DATABASE
                        + "' AND table_type='BASE TABLE' ORDER BY TABLE_NAME");
        DockerVerificationSupport.requireSuccess(listed, "Could not inspect restored tables");
        List<String> names = listed.stdout().lines().map(String::strip).filter(value -> !value.isEmpty()).toList();
        for (String encoded : names) {
            String table;
            try {
                table = new String(HexFormat.of().parseHex(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("Temporary database returned an invalid table name", e);
            }
            String quoted = table.replace("`", "``");
            ProcessRunner.Result checked = docker.execWithEnvironment(container, Duration.ofSeconds(30), environment,
                    client, "--protocol=TCP", "-h127.0.0.1", "-uroot", "--batch", "--skip-column-names", "-e",
                    "CHECK TABLE `" + DATABASE + "`.`" + quoted + "`");
            DockerVerificationSupport.requireSuccess(checked, "CHECK TABLE failed for " + table);
            if (checked.stdout().lines().noneMatch(line -> line.endsWith("\tOK"))) {
                throw new IllegalStateException("CHECK TABLE did not report OK for " + table + ": "
                        + checked.stdout().strip());
            }
        }
        return names.size();
    }
}
