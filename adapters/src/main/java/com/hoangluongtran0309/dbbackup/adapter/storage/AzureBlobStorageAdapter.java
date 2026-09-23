package com.hoangluongtran0309.dbbackup.adapter.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.azure.core.credential.TokenCredential;
import com.azure.core.http.HttpClient;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.hoangluongtran0309.dbbackup.core.model.AzureBlobStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.port.AzureBlobStoragePort;

@Component
class AzureBlobStorageAdapter implements AzureBlobStoragePort {

    @FunctionalInterface
    interface DefaultCredentialProvider {
        TokenCredential get();
    }

    private final DefaultCredentialProvider defaultCredentials;

    AzureBlobStorageAdapter() {
        this(() -> new DefaultAzureCredentialBuilder().httpClient(httpClient()).build());
    }

    AzureBlobStorageAdapter(DefaultCredentialProvider defaultCredentials) {
        this.defaultCredentials = defaultCredentials;
    }

    @Override public void test(AzureBlobStorageConnection connection) {
        String probe = prefixed(connection.keyPrefix(), ".dbbackup-probe/" + UUID.randomUUID());
        byte[] expected = "dbbackup-storage-probe".getBytes(StandardCharsets.UTF_8);
        BlobClient blob = blob(connection, probe);
        boolean created = false;
        boolean verified = false;
        try {
            try {
                blob.getBlockBlobClient().upload(new ByteArrayInputStream(expected), expected.length);
                created = true;
                if (blob.getProperties().getBlobSize() != expected.length) {
                    throw new IllegalStateException("Azure Blob probe metadata differs from what was written");
                }
                byte[] actual;
                try (InputStream input = blob.openInputStream()) {
                    actual = input.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read the Azure Blob probe", e);
                }
                if (!Arrays.equals(expected, actual)) {
                    throw new IllegalStateException(
                            "Azure Blob probe returned content different from what was written");
                }
                verified = true;
            } finally {
                if (created && verified) {
                    if (!blob.deleteIfExists()) {
                        throw new IllegalStateException("Azure Blob probe could not be deleted");
                    }
                } else if (created) {
                    try {
                        blob.deleteIfExists();
                    } catch (RuntimeException ignored) {
                        // Preserve the original probe failure; cleanup is best-effort on failure.
                    }
                }
            }
        } catch (RuntimeException e) {
            throw failure("test Azure Blob storage", e);
        }
    }

    @Override public void upload(AzureBlobStorageConnection connection, String objectKey, Path source) {
        try {
            blob(connection, objectKey).uploadFromFile(source.toString());
        } catch (RuntimeException e) {
            throw failure("upload Azure Blob artifact", e);
        }
    }

    @Override public boolean exists(AzureBlobStorageConnection connection, String objectKey) {
        try {
            return blob(connection, objectKey).exists();
        } catch (RuntimeException e) {
            throw failure("check Azure Blob artifact", e);
        }
    }

    @Override public InputStream openForReading(AzureBlobStorageConnection connection, String objectKey) {
        try {
            return blob(connection, objectKey).openInputStream();
        } catch (RuntimeException e) {
            throw failure("read Azure Blob artifact", e);
        }
    }

    @Override public void download(
            AzureBlobStorageConnection connection, String objectKey, Path destination) {
        try {
            blob(connection, objectKey).downloadToFile(destination.toString(), true);
        } catch (RuntimeException e) {
            throw failure("download Azure Blob artifact", e);
        }
    }

    @Override public void delete(AzureBlobStorageConnection connection, String objectKey) {
        try {
            blob(connection, objectKey).deleteIfExists();
        } catch (RuntimeException e) {
            throw failure("delete Azure Blob artifact", e);
        }
    }

    private BlobClient blob(AzureBlobStorageConnection connection, String objectKey) {
        return container(connection).getBlobClient(objectKey);
    }

    private BlobContainerClient container(AzureBlobStorageConnection connection) {
        String endpoint = endpointFor(connection);
        BlobServiceClientBuilder builder = new BlobServiceClientBuilder()
                .endpoint(endpoint).httpClient(httpClient());
        if (connection.credentialMode() == StorageCredentialMode.AZURE_DEFAULT) {
            builder.credential(defaultCredentials.get());
        } else if (connection.credentialMode() == StorageCredentialMode.ACCOUNT_KEY) {
            builder.credential(new StorageSharedKeyCredential(
                    connection.accountName(), connection.accountKey()));
        } else {
            throw new IllegalArgumentException("Azure profile has an invalid credential mode");
        }
        return builder.buildClient().getBlobContainerClient(connection.container());
    }

    static String endpointFor(AzureBlobStorageConnection connection) {
        return connection.endpoint() == null
                ? "https://" + connection.accountName() + ".blob.core.windows.net"
                : connection.endpoint();
    }

    private static HttpClient httpClient() {
        return new JdkHttpClientBuilder().build();
    }

    private static String prefixed(String prefix, String key) {
        return prefix == null || prefix.isBlank() ? key : prefix + "/" + key;
    }

    private static IllegalStateException failure(String action, RuntimeException exception) {
        String message = exception instanceof BlobStorageException storage
                ? storage.getServiceMessage() : exception.getMessage();
        return new IllegalStateException(
                "Could not " + action + (message == null || message.isBlank() ? "" : ": " + message),
                exception);
    }
}
