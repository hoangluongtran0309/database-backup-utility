package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/** Restores one full logical backup into a target of the same engine. */
public interface LogicalRestorePort {

    DatabaseEngine engine();

    void restore(DatabaseConnection connection, Path artifact);
}
