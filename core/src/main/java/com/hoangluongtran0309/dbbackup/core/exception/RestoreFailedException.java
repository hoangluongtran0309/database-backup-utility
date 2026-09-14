package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * The artifact was not loaded into the target.
 *
 * <p>The message carries the client's own output, because a restore that stops
 * halfway is the worst thing this tool can do and the operator needs to know
 * exactly where it stopped.
 */
public class RestoreFailedException extends RuntimeException {

    public RestoreFailedException(String message) {
        super(message);
    }

    public RestoreFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
