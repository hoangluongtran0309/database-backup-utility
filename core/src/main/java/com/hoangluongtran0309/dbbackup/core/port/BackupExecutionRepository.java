package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;

public interface BackupExecutionRepository {

    BackupExecution save(BackupExecution execution);

    Optional<BackupExecution> findById(UUID id);

    /** Most recent first. The order is part of the contract. */
    List<BackupExecution> findAllNewestFirst();

    /**
     * Executions still marked RUNNING.
     *
     * <p>Backups run in this process and nowhere else, so any row still RUNNING
     * at startup belongs to a run that died with the previous process.
     */
    List<BackupExecution> findRunning();

    /** Silent when the id is unknown. */
    void deleteById(UUID id);
}
