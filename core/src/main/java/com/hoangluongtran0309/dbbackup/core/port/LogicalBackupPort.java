package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Supplier;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Produces one full logical backup artifact. */
public interface LogicalBackupPort {

    DatabaseEngine engine();

    /** Includes its leading dot, for example {@code .sql.gz} or {@code .dump}. */
    String artifactSuffix();

    /** Writes the complete artifact and removes partial output on failure. */
    long dumpTo(DatabaseConnection connection, Path destination);

    /** Operation-aware overload used by server-side engines with durable jobs. */
    default long dumpTo(DatabaseConnection connection, Path destination, UUID operationId) {
        return dumpTo(connection, destination);
    }

    /** Stops work left behind by a previous application process; local clients have nothing to do. */
    default void abortInterrupted(UUID operationId, Supplier<DatabaseConnection> connection) {
        // no-op
    }
}
