package com.hoangluongtran0309.dbbackup.core.port;

import java.io.InputStream;
import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.AzureBlobStorageConnection;

public interface AzureBlobStoragePort {
    void test(AzureBlobStorageConnection connection);
    void upload(AzureBlobStorageConnection connection, String objectKey, Path source);
    boolean exists(AzureBlobStorageConnection connection, String objectKey);
    InputStream openForReading(AzureBlobStorageConnection connection, String objectKey);
    void download(AzureBlobStorageConnection connection, String objectKey, Path destination);
    void delete(AzureBlobStorageConnection connection, String objectKey);
}
