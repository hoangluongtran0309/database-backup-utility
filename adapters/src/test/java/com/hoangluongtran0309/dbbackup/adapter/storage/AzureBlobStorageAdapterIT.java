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

import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.hoangluongtran0309.dbbackup.core.model.AzureBlobStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;

@Testcontainers
class AzureBlobStorageAdapterIT {
    private static final int BLOB_PORT = 10000;
    private static final String ACCOUNT = "devstoreaccount1";
    private static final String ACCOUNT_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    @Container
    static final GenericContainer<?> AZURITE = new GenericContainer<>(
            DockerImageName.parse("mcr.microsoft.com/azure-storage/azurite:3.37.0"))
            .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--silent")
            .withExposedPorts(BLOB_PORT)
            .waitingFor(Wait.forListeningPort());

    @TempDir Path temp;
    private final AzureBlobStorageAdapter storage = new AzureBlobStorageAdapter();

    @BeforeAll
    static void createContainer() {
        new BlobServiceClientBuilder().endpoint(endpoint())
                .credential(new StorageSharedKeyCredential(ACCOUNT, ACCOUNT_KEY))
                .buildClient().createBlobContainer("backups");
    }

    @Test
    void probesUploadsStreamsDownloadsAndDeletes() throws Exception {
        AzureBlobStorageConnection connection = connection();
        storage.test(connection);
        assertThat(container().listBlobsByHierarchy("integration/.dbbackup-probe/"))
                .isEmpty();

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

    private static AzureBlobStorageConnection connection() {
        return new AzureBlobStorageConnection(endpoint(), ACCOUNT, "backups", "integration",
                StorageCredentialMode.ACCOUNT_KEY, ACCOUNT_KEY);
    }

    private static com.azure.storage.blob.BlobContainerClient container() {
        return new BlobServiceClientBuilder().endpoint(endpoint())
                .credential(new StorageSharedKeyCredential(ACCOUNT, ACCOUNT_KEY))
                .buildClient().getBlobContainerClient("backups");
    }

    private static String endpoint() {
        return "http://" + AZURITE.getHost() + ":" + AZURITE.getMappedPort(BLOB_PORT) + "/" + ACCOUNT;
    }
}
