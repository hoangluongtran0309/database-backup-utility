package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Restores one full logical backup into a target of the same engine. */
public interface LogicalRestorePort {

    DatabaseEngine engine();

    /**
     * @param sourceNamespace namespace carried by the artifact; engines
     *        whose archive is namespace-independent may ignore it
     */
    void restore(DatabaseConnection connection, String sourceNamespace, Path artifact);

    /** Operation-aware overload used by server-side engines with durable jobs. */
    default void restore(
            DatabaseConnection connection, String sourceNamespace, Path artifact, UUID operationId) {
        restore(connection, sourceNamespace, artifact);
    }

    /** Stops work left behind by a previous application process; local clients have nothing to do. */
    default void abortInterrupted(UUID operationId, Supplier<DatabaseConnection> connection) {
        // no-op
    }

    /** Same-database restore convenience retained for direct adapter callers. */
    default void restore(DatabaseConnection connection, Path artifact) {
        restore(connection, connection.database(), artifact);
    }
}
