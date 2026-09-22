package com.hoangluongtran0309.dbbackup.core.exception;

import lombok.Getter;

/** A schedule field that cannot form a Quartz cron trigger. */
@Getter
public class InvalidScheduleException extends RuntimeException {

    private final String field;

    public InvalidScheduleException(String field, String message) {
        super(message);
        this.field = field;
    }
}
