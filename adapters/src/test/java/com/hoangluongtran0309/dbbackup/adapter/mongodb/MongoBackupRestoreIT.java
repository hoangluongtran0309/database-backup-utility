package com.hoangluongtran0309.dbbackup.adapter.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.hoangluongtran0309.dbbackup.adapter.process.ProcessRunner;
import com.hoangluongtran0309.dbbackup.adapter.verification.DockerVerificationSupport;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

/** Real MongoDB plus the same host Database Tools used in production. */
@Testcontainers
class MongoBackupRestoreIT {

    private static final String USERNAME = "root";
    private static final String PASSWORD = "s3cr3t-pāss";
    private static final Path MONGODUMP = Path.of("/usr/bin/mongodump");
    private static final Path MONGORESTORE = Path.of("/usr/bin/mongorestore");

    @Container
    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:8.0")
            .withEnv("MONGO_INITDB_ROOT_USERNAME", USERNAME)
            .withEnv("MONGO_INITDB_ROOT_PASSWORD", PASSWORD)
            .withExposedPorts(27017);

    @TempDir
    Path artifacts;

    private MongoCliConnectionTestAdapter connectionTest;
    private MongoDumpBackupAdapter backup;
    private MongoRestoreAdapter restore;

    @BeforeAll
    static void requireClientBinariesAndAuthenticatedMongo() throws Exception {
        assertThat(MONGODUMP).isExecutable();
        assertThat(MONGORESTORE).isExecutable();

        // The official image briefly starts an unauthenticated mongod while
        // creating the root user. A listening-port or log wait can therefore
        // finish before authentication is ready, depending on where Docker
        // sends the temporary server's logs. Probe the actual contract.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        long readySince = 0;
        boolean stablyReady = false;
        org.testcontainers.containers.Container.ExecResult result = null;
        String lastProblem = "MongoDB did not become ready";
        do {
            try {
                result = MONGO.execInContainer(
                        "mongosh", "--quiet", "mongodb://127.0.0.1:27017/admin",
                        "--username", USERNAME,
                        "--password", PASSWORD,
                        "--authenticationDatabase", "admin",
                        "--eval", "db.runCommand({ping: 1})");
                lastProblem = result.getStderr();
            } catch (RuntimeException bootstrapRestart) {
                // The official image stops its bootstrap mongod before the
                // authenticated final server starts. Docker reports 409 if a
                // probe lands in that short no-process window; it is not a
                // failed readiness contract yet.
                result = null;
                lastProblem = bootstrapRestart.getMessage();
            }
            if (result != null && result.getExitCode() == 0) {
                if (readySince == 0) {
                    readySince = System.nanoTime();
                } else if (System.nanoTime() - readySince >= TimeUnit.SECONDS.toNanos(2)) {
                    stablyReady = true;
                    break;
                }
            } else {
                // The temporary bootstrap server can accept one authenticated
                // command immediately before it shuts down. Only a continuous
                // ready window proves that the final server is running.
                readySince = 0;
            }
            Thread.sleep(250);
        } while (System.nanoTime() < deadline);

        assertThat(stablyReady).as(lastProblem).isTrue();
    }

    @BeforeEach
    void setUp() throws Exception {
        ProcessRunner runner = new ProcessRunner();
        connectionTest = new MongoCliConnectionTestAdapter(runner, MONGODUMP, Duration.ofSeconds(10));
        backup = new MongoDumpBackupAdapter(runner, MONGODUMP, Duration.ofMinutes(2));
        restore = new MongoRestoreAdapter(runner, MONGORESTORE, Duration.ofMinutes(2));

        eval("shop_source", "db.dropDatabase()");
        eval("shop_restore", "db.dropDatabase()");
        eval("shop_source", """
                db.widgets.insertMany([
                  {_id: 1, name: 'alpha'},
                  {_id: 2, name: "O'Brien"},
                  {_id: 3, name: 'Đà Nẵng'}
                ]);
                db.widgets.createIndex({name: 1}, {unique: true});
                """);
        eval("shop_restore", """
                db.widgets.insertOne({_id: 99, name: 'later'});
                db.unrelated.insertOne({_id: 1, keep: true});
                """);
    }

    @Test
    void testsConnectionDumpsAndRestoresIntoAnotherMongoDatabase() throws Exception {
        assertThat(connectionTest.test(target("shop_source")).successful()).isTrue();
        Path artifact = artifacts.resolve("shop.archive.gz");

        long size = backup.dumpTo(target("shop_source"), artifact);
        assertThat(size).isPositive();
        assertThat(artifact).isNotEmptyFile();

        restore.restore(target("shop_restore"), "shop_source", artifact);

        assertThat(eval("shop_restore", "db.widgets.countDocuments({})")).endsWith("3");
        assertThat(eval("shop_restore", "db.widgets.find().sort({_id:1}).toArray().map(d => d.name).join(',')"))
                .endsWith("alpha,O'Brien,Đà Nẵng");
        assertThat(eval("shop_restore", "db.widgets.getIndexes().some(i => i.name === 'name_1' && i.unique)"))
                .endsWith("true");
        assertThat(eval("shop_restore", "db.unrelated.countDocuments({})")).endsWith("1");
        assertThat(eval("shop_source", "db.widgets.countDocuments({})")).endsWith("3");
    }

    @Test
    void verifiesARealBackupInADisposableMongoContainerAndRemovesIt() {
        Path artifact = artifacts.resolve("shop.archive.gz");
        backup.dumpTo(target("shop_source"), artifact);
        UUID verificationId = UUID.randomUUID();
        DockerVerificationSupport docker = dockerSupport();

        var result = new MongoRestoreVerificationAdapter(
                docker, true, "mongo:8.0", Duration.ofMinutes(2))
                .verify(verificationId, sourceTarget(), artifact);

        assertThat(result.checkedObjects()).isEqualTo(1);
        assertThat(docker.success(List.of("docker", "inspect",
                DockerVerificationSupport.containerName(DatabaseEngine.MONGODB, verificationId)),
                Duration.ofSeconds(10))).isFalse();

        UUID corruptId = UUID.randomUUID();
        Path corrupt = artifacts.resolve("corrupt-verification.archive.gz");
        try {
            java.nio.file.Files.writeString(corrupt, "not a mongo archive");
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        assertThatThrownBy(() -> new MongoRestoreVerificationAdapter(
                docker, true, "mongo:8.0", Duration.ofMinutes(2))
                .verify(corruptId, sourceTarget(), corrupt))
                .hasMessageContaining("mongorestore failed");
        assertThat(docker.success(List.of("docker", "inspect",
                DockerVerificationSupport.containerName(DatabaseEngine.MONGODB, corruptId)),
                Duration.ofSeconds(10))).isFalse();
    }

    private static DatabaseConnection target(String database) {
        return new DatabaseConnection(
                DatabaseEngine.MONGODB,
                MONGO.getHost(),
                MONGO.getMappedPort(27017),
                database,
                USERNAME,
                PASSWORD,
                "admin");
    }

    private static DatabaseTarget sourceTarget() {
        return DatabaseTarget.builder().id(UUID.randomUUID()).name("source").engine(DatabaseEngine.MONGODB)
                .host("unused").port(27017).databaseName("shop_source").username("unused")
                .authenticationDatabase("admin").passwordCiphertext("unused").createdAt(Instant.EPOCH).build();
    }

    private static DockerVerificationSupport dockerSupport() {
        return new DockerVerificationSupport(new ProcessRunner(), Duration.ofMinutes(10),
                Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    private static String eval(String database, String javascript) throws Exception {
        org.testcontainers.containers.Container.ExecResult result = MONGO.execInContainer(
                "mongosh",
                "--quiet",
                "mongodb://127.0.0.1:27017/" + database,
                "--username", USERNAME,
                "--password", PASSWORD,
                "--authenticationDatabase", "admin",
                "--eval", javascript);
        assertThat(result.getExitCode()).as(result.getStderr()).isZero();
        return result.getStdout().strip();
    }
}
