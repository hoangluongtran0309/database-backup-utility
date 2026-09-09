package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * A {@code DatabaseTarget} was asked to exist in a state it may not hold.
 *
 * <p>Carries the offending field name so the web layer can attach the message
 * to the right input instead of dumping it into a page-level summary.
 */
public class InvalidTargetException extends RuntimeException {

    private final String field;

    public InvalidTargetException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
