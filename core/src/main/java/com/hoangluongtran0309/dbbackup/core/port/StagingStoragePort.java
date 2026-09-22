package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;
import java.util.UUID;

public interface StagingStoragePort {
    Path locationFor(UUID operationId, String filename);
    long sizeOf(Path path);
    String sha256Of(Path path);
    void deleteOperation(UUID operationId);
}
