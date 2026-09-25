package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationPort;

@Component
class MongoRestoreVerificationAdapter implements RestoreVerificationPort {
    private final DockerVerificationSupport docker;
    private final boolean enabled;
    private final String image;
    private final Duration timeout;

    MongoRestoreVerificationAdapter(DockerVerificationSupport docker,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled,
            @Value("${dbbackup.verification.mongodb-image:mongo:8.0}") String image,
            @Value("${dbbackup.restore.timeout}") Duration timeout) {
        this.docker = docker; this.enabled = enabled; this.image = image; this.timeout = timeout;
    }

    @Override public DatabaseEngine engine() { return DatabaseEngine.MONGODB; }
    @Override public boolean isAvailable() { return enabled && docker.available(); }

    @Override
    public RestoreVerificationResult verify(UUID id, DatabaseTarget source, Path artifact) {
        String container = null;
        RuntimeException failure = null;
        try {
            container = docker.start(engine(), id, image, Map.of());
            docker.waitUntilReady(container, Map.of(), "mongosh", "--quiet", "--eval",
                    "quit(db.adminCommand({ping:1}).ok === 1 ? 0 : 1)");
            docker.copy(artifact, container, "/tmp/backup.archive.gz", Duration.ofMinutes(2));
            ProcessRunner.Result restored = docker.exec(container, timeout, "mongorestore",
                    "--archive=/tmp/backup.archive.gz", "--gzip", "--drop", "--stopOnError");
            DockerVerificationSupport.requireSuccess(restored, "mongorestore failed");
            String database = js(source.getDatabaseName());
            String script = """
                    const d = db.getSiblingDB(%s);
                    const names = d.getCollectionNames().filter(n => !n.startsWith('system.'));
                    for (const name of names) {
                      const result = d.runCommand({validate: name, full: false});
                      if (!result.ok || result.valid === false) { printjson(result); quit(2); }
                    }
                    print('DBBACKUP_CHECKED=' + names.length);
                    """.formatted(database);
            ProcessRunner.Result checked = docker.exec(container, Duration.ofMinutes(5),
                    "mongosh", "--quiet", "--eval", script);
            DockerVerificationSupport.requireSuccess(checked, "MongoDB collection validation failed");
            String marker = checked.stdout().lines().filter(line -> line.startsWith("DBBACKUP_CHECKED="))
                    .findFirst().orElseThrow(() -> new IllegalStateException(
                            "MongoDB validation did not report its collection count"));
            int count = Integer.parseInt(marker.substring("DBBACKUP_CHECKED=".length()));
            return new RestoreVerificationResult(count,
                    "Restored successfully and validated %d MongoDB collection(s)".formatted(count));
        } catch (RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try { docker.remove(engine(), id); }
            catch (RuntimeException cleanup) {
                if (failure != null) failure.addSuppressed(cleanup); else throw cleanup;
            }
        }
    }

    @Override public void abortInterrupted(UUID id) { docker.remove(engine(), id); }

    private static String js(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
