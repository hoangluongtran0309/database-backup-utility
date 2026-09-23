package com.hoangluongtran0309.dbbackup.core.port;

import java.io.InputStream;
import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.GcsStorageConnection;

public interface GcsStoragePort {
    void test(GcsStorageConnection connection);
    void upload(GcsStorageConnection connection, String objectKey, Path source);
    boolean exists(GcsStorageConnection connection, String objectKey);
    InputStream openForReading(GcsStorageConnection connection, String objectKey);
    void download(GcsStorageConnection connection, String objectKey, Path destination);
    void delete(GcsStorageConnection connection, String objectKey);
}
