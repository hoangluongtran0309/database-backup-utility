package com.hoangluongtran0309.dbbackup.core.port;

import java.io.InputStream;
import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.S3StorageConnection;

public interface S3StoragePort {
    void test(S3StorageConnection connection);
    void upload(S3StorageConnection connection, String objectKey, Path source);
    boolean exists(S3StorageConnection connection, String objectKey);
    InputStream openForReading(S3StorageConnection connection, String objectKey);
    void download(S3StorageConnection connection, String objectKey, Path destination);
    void delete(S3StorageConnection connection, String objectKey);
}
