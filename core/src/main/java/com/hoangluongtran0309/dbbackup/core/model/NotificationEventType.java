package com.hoangluongtran0309.dbbackup.core.model;

public enum NotificationEventType {
    BACKUP_STARTED,
    BACKUP_SUCCESS,
    BACKUP_FAILED,
    RESTORE_STARTED,
    RESTORE_SUCCESS,
    RESTORE_FAILED,
    VERIFICATION_SUCCESS,
    VERIFICATION_FAILED,
    TEST;

    public boolean isSubscribable() { return this != TEST; }
}
