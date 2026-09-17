package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Produces one full logical backup artifact. */
public interface LogicalBackupPort {

    DatabaseEngine engine();

    /** Includes its leading dot, for example {@code .sql.gz} or {@code .dump}. */
    String artifactSuffix();

    /** Writes the complete artifact and removes partial output on failure. */
    long dumpTo(DatabaseConnection connection, Path destination);
}
