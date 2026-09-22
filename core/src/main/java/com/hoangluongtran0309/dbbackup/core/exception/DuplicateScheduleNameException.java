package com.hoangluongtran0309.dbbackup.core.exception;

/** Raised when two backup schedules would have the same operator-facing name. */
public class DuplicateScheduleNameException extends RuntimeException {

    public DuplicateScheduleNameException(String name) {
        super("A backup schedule named '%s' already exists".formatted(name));
    }
}
