package com.hoangluongtran0309.dbbackup.adapter.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.hoangluongtran0309.dbbackup.core.model.GcsStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;

@Testcontainers
class GcsStorageAdapterIT {
    private static final int HTTP_PORT = 4443;
    private static final String PROJECT = "dbbackup-integration";

    @Container
    static final GenericContainer<?> GCS = new GenericContainer<>(
            DockerImageName.parse("fsouza/fake-gcs-server:1.54.0"))
            .withCommand("-scheme", "http", "-port", Integer.toString(HTTP_PORT))
            .withExposedPorts(HTTP_PORT)
            .waitingFor(Wait.forListeningPort());

    @TempDir Path temp;
    private final GcsStorageAdapter storage = new GcsStorageAdapter(ignored -> NoCredentials.getInstance());

    @BeforeAll
    static void createBucket() {
        client().create(BucketInfo.of("backups"));
    }

    @Test
    void probesUploadsStreamsDownloadsAndDeletes() throws Exception {
        GcsStorageConnection connection = connection();
        storage.test(connection);
        assertThat(client().list("backups", Storage.BlobListOption.prefix("integration/.dbbackup-probe/"))
                .iterateAll()).isEmpty();

        byte[] content = new byte[20 * 1024 * 1024];
        new Random(42).nextBytes(content);
        Path source = temp.resolve("source.bin");
        Files.write(source, content);
        String key = "integration/target/execution/source.bin";

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

    private static GcsStorageConnection connection() {
        return new GcsStorageConnection(endpoint(), PROJECT, "backups", "integration",
                StorageCredentialMode.APPLICATION_DEFAULT, null);
    }

    private static Storage client() {
        return StorageOptions.newBuilder().setHost(endpoint()).setProjectId(PROJECT)
                .setCredentials(NoCredentials.getInstance()).build().getService();
    }

    private static String endpoint() {
        return "http://" + GCS.getHost() + ":" + GCS.getMappedPort(HTTP_PORT);
    }
}
