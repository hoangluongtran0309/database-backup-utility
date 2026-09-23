package com.hoangluongtran0309.dbbackup.adapter.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.google.auth.Credentials;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;
import com.hoangluongtran0309.dbbackup.core.model.GcsStorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.port.GcsStoragePort;

@Component
class GcsStorageAdapter implements GcsStoragePort {

    @FunctionalInterface
    interface CredentialResolver {
        Credentials resolve(GcsStorageConnection connection) throws IOException;
    }

    @FunctionalInterface
    interface ApplicationDefaultProvider {
        Credentials get() throws IOException;
    }

    private final CredentialResolver credentials;

    GcsStorageAdapter() {
        this(GcsStorageAdapter::resolveCredentials);
    }

    GcsStorageAdapter(CredentialResolver credentials) {
        this.credentials = credentials;
    }

    @Override public void test(GcsStorageConnection connection) {
        String probe = prefixed(connection.keyPrefix(), ".dbbackup-probe/" + UUID.randomUUID());
        byte[] expected = "dbbackup-storage-probe".getBytes(StandardCharsets.UTF_8);
        Storage client = client(connection);
        BlobId id = BlobId.of(connection.bucket(), probe);
        try {
            boolean created = false;
            boolean verified = false;
            try {
                client.create(BlobInfo.newBuilder(id).build(), expected);
                created = true;
                Blob metadata = client.get(id);
                if (metadata == null || metadata.getSize() != expected.length) {
                    throw new IllegalStateException("GCS probe metadata differs from what was written");
                }
                byte[] actual;
                try (InputStream input = Channels.newInputStream(client.reader(id))) {
                    actual = input.readAllBytes();
                } catch (IOException e) {
                    throw new UncheckedIOException("Could not read the GCS probe", e);
                }
                if (!Arrays.equals(expected, actual)) {
                    throw new IllegalStateException("GCS probe returned content different from what was written");
                }
                verified = true;
            } finally {
                if (created && verified) {
                    if (!client.delete(id)) {
                        throw new IllegalStateException("GCS probe could not be deleted");
                    }
                } else if (created) {
                    try {
                        client.delete(id);
                    } catch (RuntimeException ignored) {
                        // Preserve the probe failure; cleanup is best-effort on an already failing connection.
                    }
                }
            }
        } catch (RuntimeException e) {
            throw failure("test GCS storage", e);
        }
    }

    @Override public void upload(GcsStorageConnection connection, String objectKey, Path source) {
        try {
            client(connection).createFrom(
                    BlobInfo.newBuilder(connection.bucket(), objectKey).build(), source);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not upload GCS artifact", e);
        } catch (RuntimeException e) {
            throw failure("upload GCS artifact", e);
        }
    }

    @Override public boolean exists(GcsStorageConnection connection, String objectKey) {
        try {
            return client(connection).get(BlobId.of(connection.bucket(), objectKey)) != null;
        } catch (RuntimeException e) {
            throw failure("check GCS artifact", e);
        }
    }

    @Override public InputStream openForReading(GcsStorageConnection connection, String objectKey) {
        try {
            return Channels.newInputStream(client(connection).reader(
                    BlobId.of(connection.bucket(), objectKey)));
        } catch (RuntimeException e) {
            throw failure("read GCS artifact", e);
        }
    }

    @Override public void download(GcsStorageConnection connection, String objectKey, Path destination) {
        try {
            client(connection).downloadTo(BlobId.of(connection.bucket(), objectKey), destination);
        } catch (RuntimeException e) {
            throw failure("download GCS artifact", e);
        }
    }

    @Override public void delete(GcsStorageConnection connection, String objectKey) {
        try {
            client(connection).delete(BlobId.of(connection.bucket(), objectKey));
        } catch (RuntimeException e) {
            throw failure("delete GCS artifact", e);
        }
    }

    private Storage client(GcsStorageConnection connection) {
        try {
            StorageOptions.Builder builder = StorageOptions.newBuilder()
                    .setProjectId(connection.projectId())
                    .setCredentials(credentials.resolve(connection));
            if (connection.endpoint() != null) builder.setHost(connection.endpoint());
            return builder.build().getService();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not load GCS credentials", e);
        }
    }

    static Credentials resolveCredentials(GcsStorageConnection connection) throws IOException {
        return resolveCredentials(connection, GoogleCredentials::getApplicationDefault);
    }

    static Credentials resolveCredentials(
            GcsStorageConnection connection, ApplicationDefaultProvider applicationDefault) throws IOException {
        if (connection.credentialMode() == StorageCredentialMode.APPLICATION_DEFAULT) {
            return applicationDefault.get();
        }
        if (connection.credentialMode() != StorageCredentialMode.SERVICE_ACCOUNT_JSON) {
            throw new IllegalArgumentException("GCS profile has an invalid credential mode");
        }
        return ServiceAccountCredentials.fromStream(new java.io.ByteArrayInputStream(
                connection.serviceAccountJson().getBytes(StandardCharsets.UTF_8)));
    }

    private static String prefixed(String prefix, String key) {
        return prefix == null || prefix.isBlank() ? key : prefix + "/" + key;
    }

    private static IllegalStateException failure(String action, RuntimeException exception) {
        String message = exception instanceof StorageException storage ? storage.getMessage() : exception.getMessage();
        return new IllegalStateException("Could not " + action + (message == null ? "" : ": " + message), exception);
    }
}
