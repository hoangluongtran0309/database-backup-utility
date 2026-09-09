package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * The dump did not produce a usable artifact.
 *
 * <p>The message is intended to be shown to an operator and stored, so it
 * carries the tool's own output rather than a summary — and never the password.
 */
public class BackupFailedException extends RuntimeException {

    public BackupFailedException(String message) {
        super(message);
    }
}
