package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;

public interface RestoreExecutionRepository {

    RestoreExecution save(RestoreExecution execution);

    Optional<RestoreExecution> findById(UUID id);

    /**
     * One page of the history, most recent first.
     *
     * @param page 1-based
     * @see BackupExecutionRepository#findNewestFirst(int, int)
     */
    HistoryPage<RestoreExecution> findNewestFirst(int page, int pageSize);

    /** @see BackupExecutionRepository#findRunning() */
    List<RestoreExecution> findRunning();

    /** How many restores refer to this backup. Shown before it is deleted. */
    long countForBackup(UUID backupExecutionId);

    /**
     * Removes every restore record referring to this backup.
     *
     * <p>Called only as part of deleting the backup itself: the foreign key
     * would otherwise refuse, and a restore record pointing at a backup that
     * no longer exists says less than nothing.
     */
    void deleteForBackup(UUID backupExecutionId);

    /**
     * Removes every restore record whose data went into this target.
     *
     * <p>Called only as part of removing the target itself, which the operator
     * has confirmed. See ADR-014.
     */
    void deleteForTarget(UUID targetId);
}
