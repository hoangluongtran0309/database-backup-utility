package com.hoangluongtran0309.dbbackup.core.exception;

public final class NotificationChannelInUseException extends RuntimeException {
    public NotificationChannelInUseException(String name) {
        super("Notification channel '" + name + "' is still referenced by a database target");
    }
}
