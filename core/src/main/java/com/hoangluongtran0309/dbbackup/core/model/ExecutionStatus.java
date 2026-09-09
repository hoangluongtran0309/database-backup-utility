package com.hoangluongtran0309.dbbackup.core.model;

/**
 * Where a long-running job got to. Shared by backups and restores: the three
 * states mean the same thing for both, and a second identical enum would only
 * be a second place to keep in step.
 *
 * <p>{@code RUNNING} covers accepted-and-not-yet-finished, including the brief
 * period a job spends queued behind another. Splitting out a QUEUED state would
 * buy an extra database write and an extra label for a distinction nobody can
 * act on.
 */
public enum ExecutionStatus {
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isFinished() {
        return this != RUNNING;
    }
}
