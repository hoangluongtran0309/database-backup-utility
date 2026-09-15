package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * A target could not be removed because backups of it still exist.
 *
 * <p>Deliberate: the backups are the valuable thing here, and deleting a target
 * must not be a way to lose them by accident. They go with it only when the
 * operator has confirmed that, by typing the target's name (ADR-015).
 */
public class TargetInUseException extends RuntimeException {

    public TargetInUseException(String name) {
        super("'%s' still has backups. Removing it deletes them too — type its name to confirm.".formatted(name));
    }
}
