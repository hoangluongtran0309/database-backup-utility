package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;

import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;

/**
 * Loads a backup artifact into a target's schema.
 */
public interface MysqlLogicalRestorePort {

    /**
     * Applies {@code artifact} to {@code connection}'s schema.
     *
     * <p>This overwrites: the dump recreates every table it contains. It is
     * not a reset — tables the dump does not mention are left exactly as they
     * were. Whoever calls this has already confirmed that with a human.
     *
     * @param artifact a gzipped SQL dump produced by
     *                 {@link MysqlLogicalBackupPort}
     * @throws com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException
     *         if the artifact could not be read, or the client reported an error
     */
    void restore(MysqlConnection connection, Path artifact);
}
