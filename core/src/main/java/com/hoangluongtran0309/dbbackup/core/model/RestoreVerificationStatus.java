package com.hoangluongtran0309.dbbackup.core.model;

/** Lifecycle of one isolated restore-verification attempt. */
public enum RestoreVerificationStatus {
    RUNNING,
    SUCCEEDED,
    FAILED;

    public boolean isFinished() {
        return this != RUNNING;
    }
}
