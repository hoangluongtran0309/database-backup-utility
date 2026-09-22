package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.containers.wait.strategy.Wait;

import com.hoangluongtran0309.dbbackup.core.model.S3StorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Testcontainers
class S3StorageAdapterIT {
    private static final int HTTP_PORT = 9090;

    @Container
    static final GenericContainer<?> S3 = new GenericContainer<>(DockerImageName.parse("adobe/s3mock:5.1.0"))
            .withEnv("initialBuckets", "backups")
            .withExposedPorts(HTTP_PORT)
            .waitingFor(Wait.forListeningPort());

    @TempDir Path temp;
    private final S3StorageAdapter storage = new S3StorageAdapter();

    @BeforeAll
    static void createBucket() {
        try (S3Client client = S3Client.builder().endpointOverride(java.net.URI.create(endpoint()))
                .region(Region.US_EAST_1).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("access", "secret")))
                .build()) {
            client.createBucket(b -> b.bucket("backups"));
        }
    }

    @Test
    void probesUploadsStreamsDownloadsAndDeletesWithStaticCredentials() throws Exception {
        S3StorageConnection connection = connection(StorageCredentialMode.STATIC);
        storage.test(connection);

        byte[] content = new byte[10 * 1024 * 1024]; // large enough to exercise multipart
        new Random(42).nextBytes(content);
        Path source = temp.resolve("source.bin");
        Files.write(source, content);
        String key = "daily/target/execution/source.bin";

        storage.upload(connection, key, source);
        assertThat(storage.exists(connection, key)).isTrue();
        try (var input = storage.openForReading(connection, key)) {
            assertThat(input.readAllBytes()).isEqualTo(content);
        }
        Path downloaded = temp.resolve("downloaded.bin");
        storage.download(connection, key, downloaded);
        assertThat(Files.readAllBytes(downloaded)).isEqualTo(content);
        storage.delete(connection, key);
        assertThat(storage.exists(connection, key)).isFalse();
    }

    @Test
    void defaultCredentialChainWorksWithTheSameCustomEndpoint() {
        String oldAccess = System.getProperty("aws.accessKeyId");
        String oldSecret = System.getProperty("aws.secretAccessKey");
        System.setProperty("aws.accessKeyId", "default-chain-access");
        System.setProperty("aws.secretAccessKey", "default-chain-secret");
        try {
            storage.test(connection(StorageCredentialMode.DEFAULT_CHAIN));
        } finally {
            restore("aws.accessKeyId", oldAccess);
            restore("aws.secretAccessKey", oldSecret);
        }
    }

    private static S3StorageConnection connection(StorageCredentialMode mode) {
        return new S3StorageConnection(endpoint(),
                "us-east-1", "backups", "integration", true, mode,
                mode == StorageCredentialMode.STATIC ? "access" : null,
                mode == StorageCredentialMode.STATIC ? "secret" : null);
    }

    private static String endpoint() {
        return "http://" + S3.getHost() + ":" + S3.getMappedPort(HTTP_PORT);
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key); else System.setProperty(key, value);
    }
}
