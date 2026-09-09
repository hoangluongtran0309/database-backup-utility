package com.hoangluongtran0309.dbbackup.core.model;

/**
 * Where a backup got to.
 *
 * <p>{@code RUNNING} covers accepted-and-not-yet-finished, including the brief
 * period a job spends queued behind another. Splitting out a QUEUED state would
 * buy an extra database write and an extra label for a distinction nobody can
 * act on.
 */
public enum BackupStatus {
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isFinished() {
        return this != RUNNING;
    }
}
