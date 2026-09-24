package com.hoangluongtran0309.dbbackup.core.exception;

public final class DuplicateNotificationChannelNameException extends RuntimeException {
    public DuplicateNotificationChannelNameException(String name) {
        super("A notification channel named '" + name + "' already exists");
    }
}
