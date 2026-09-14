package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;

/**
 * Produces a logical dump of a target's schema.
 */
public interface MysqlLogicalBackupPort {

    /**
     * Writes a dump of {@code connection}'s schema to {@code destination}.
     *
     * <p>On failure the implementation must not leave a partial file behind: a
     * truncated dump that looks like a backup is worse than no backup.
     *
     * @return the number of bytes written
     * @throws com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException
     *         if the dump did not complete
     */
    long dumpTo(MysqlConnection connection, Path destination);
}
