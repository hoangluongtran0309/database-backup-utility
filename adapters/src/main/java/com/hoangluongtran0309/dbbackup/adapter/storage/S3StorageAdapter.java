package com.hoangluongtran0309.dbbackup.adapter.storage;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.S3StorageConnection;
import com.hoangluongtran0309.dbbackup.core.model.StorageCredentialMode;
import com.hoangluongtran0309.dbbackup.core.port.S3StoragePort;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

@Component
class S3StorageAdapter implements S3StoragePort {

    @Override public void test(S3StorageConnection connection) {
        String probe = ".dbbackup-probe/" + UUID.randomUUID();
        String key = connection.keyPrefix() == null || connection.keyPrefix().isBlank()
                ? probe : connection.keyPrefix() + "/" + probe;
        byte[] expected = "dbbackup-storage-probe".getBytes(StandardCharsets.UTF_8);
        try (S3Client client = sync(connection)) {
            boolean verified = false;
            try {
                client.putObject(b -> b.bucket(connection.bucket()).key(key), RequestBody.fromBytes(expected));
                long length = client.headObject(b -> b.bucket(connection.bucket()).key(key)).contentLength();
                byte[] actual = client.getObjectAsBytes(b -> b.bucket(connection.bucket()).key(key)).asByteArray();
                if (length != expected.length || !Arrays.equals(expected, actual)) {
                    throw new IllegalStateException("S3 probe returned content different from what was written");
                }
                verified = true;
            } finally {
                if (verified) {
                    client.deleteObject(b -> b.bucket(connection.bucket()).key(key));
                } else {
                    try {
                        client.deleteObject(b -> b.bucket(connection.bucket()).key(key));
                    } catch (RuntimeException ignored) {
                        // Preserve the put/head/get failure; lifecycle is the crash backstop.
                    }
                }
            }
        } catch (RuntimeException e) {
            throw failure("test S3 storage", e);
        }
    }

    @Override public void upload(S3StorageConnection connection, String objectKey, Path source) {
        try (S3AsyncClient client = async(connection);
                S3TransferManager transfers = S3TransferManager.builder().s3Client(client).build()) {
            transfers.uploadFile(UploadFileRequest.builder()
                    .putObjectRequest(b -> b.bucket(connection.bucket()).key(objectKey))
                    .source(source).build()).completionFuture().join();
        } catch (RuntimeException e) {
            throw failure("upload S3 artifact", e);
        }
    }

    @Override public boolean exists(S3StorageConnection connection, String objectKey) {
        try (S3Client client = sync(connection)) {
            client.headObject(HeadObjectRequest.builder().bucket(connection.bucket()).key(objectKey).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) return false;
            throw failure("check S3 artifact", e);
        }
    }

    @Override public InputStream openForReading(S3StorageConnection connection, String objectKey) {
        S3Client client = sync(connection);
        try {
            ResponseInputStream<GetObjectResponse> stream = client.getObject(
                    GetObjectRequest.builder().bucket(connection.bucket()).key(objectKey).build());
            return new FilterInputStream(stream) {
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        client.close();
                    }
                }
            };
        } catch (RuntimeException e) {
            client.close();
            throw failure("read S3 artifact", e);
        }
    }

    @Override public void download(S3StorageConnection connection, String objectKey, Path destination) {
        try (S3AsyncClient client = async(connection);
                S3TransferManager transfers = S3TransferManager.builder().s3Client(client).build()) {
            transfers.downloadFile(DownloadFileRequest.builder()
                    .getObjectRequest(b -> b.bucket(connection.bucket()).key(objectKey))
                    .destination(destination).build()).completionFuture().join();
        } catch (RuntimeException e) {
            throw failure("download S3 artifact", e);
        }
    }

    @Override public void delete(S3StorageConnection connection, String objectKey) {
        try (S3Client client = sync(connection)) {
            client.deleteObject(b -> b.bucket(connection.bucket()).key(objectKey));
        } catch (RuntimeException e) {
            throw failure("delete S3 artifact", e);
        }
    }

    private static S3Client sync(S3StorageConnection connection) {
        var builder = S3Client.builder().region(Region.of(connection.region()))
                .credentialsProvider(credentials(connection)).forcePathStyle(connection.pathStyle());
        if (connection.endpoint() != null) builder.endpointOverride(URI.create(connection.endpoint()));
        return builder.build();
    }

    private static S3AsyncClient async(S3StorageConnection connection) {
        var builder = S3AsyncClient.builder().region(Region.of(connection.region()))
                .credentialsProvider(credentials(connection)).forcePathStyle(connection.pathStyle())
                .multipartEnabled(true);
        if (connection.endpoint() != null) builder.endpointOverride(URI.create(connection.endpoint()));
        return builder.build();
    }

    private static AwsCredentialsProvider credentials(S3StorageConnection connection) {
        return connection.credentialMode() == StorageCredentialMode.DEFAULT_CHAIN
                ? DefaultCredentialsProvider.create()
                : StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        connection.accessKeyId(), connection.secretAccessKey()));
    }

    private static IllegalStateException failure(String action, RuntimeException exception) {
        Throwable cause = exception instanceof CompletionException && exception.getCause() != null
                ? exception.getCause() : exception;
        String message = cause.getMessage();
        return new IllegalStateException("Could not " + action + (message == null ? "" : ": " + message), cause);
    }
}
