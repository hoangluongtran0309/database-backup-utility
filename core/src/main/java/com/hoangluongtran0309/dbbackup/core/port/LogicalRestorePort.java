package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Restores one full logical backup into a target of the same engine. */
public interface LogicalRestorePort {

    DatabaseEngine engine();

    /**
     * @param sourceDatabase database namespace carried by the artifact; engines
     *        whose archive is namespace-independent may ignore it
     */
    void restore(DatabaseConnection connection, String sourceDatabase, Path artifact);

    /** Same-database restore convenience retained for direct adapter callers. */
    default void restore(DatabaseConnection connection, Path artifact) {
        restore(connection, connection.database(), artifact);
    }
}
