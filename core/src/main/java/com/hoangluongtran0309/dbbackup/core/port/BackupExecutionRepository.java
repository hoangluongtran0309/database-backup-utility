package com.hoangluongtran0309.dbbackup.core.port;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;

public interface BackupExecutionRepository {

    BackupExecution save(BackupExecution execution);

    Optional<BackupExecution> findById(UUID id);

    /**
     * One page of the history, most recent first — ties broken by id, so the
     * order is stable. The order is part of the contract.
     *
     * @param page 1-based
     */
    HistoryPage<BackupExecution> findNewestFirst(int page, int pageSize);

    /** Each target's newest backup attempt, in any state. One per target at most. */
    List<BackupExecution> findLatestPerTarget();

    /** Each target's newest successful backup. One per target at most. */
    List<BackupExecution> findLatestSucceededPerTarget();

    /** The ones that exist, in no particular order; unknown ids are skipped. */
    List<BackupExecution> findAllById(Collection<UUID> ids);

    /**
     * Executions still marked RUNNING.
     *
     * <p>Backups run in this process and nowhere else, so any row still RUNNING
     * at startup belongs to a run that died with the previous process.
     */
    List<BackupExecution> findRunning();

    /**
     * Every backup, in any state, taken of this target, in no particular order.
     * Rows only — reading them touches no artifact.
     */
    List<BackupExecution> findAllForTarget(UUID targetId);

    /** Silent when the id is unknown. */
    void deleteById(UUID id);
}
