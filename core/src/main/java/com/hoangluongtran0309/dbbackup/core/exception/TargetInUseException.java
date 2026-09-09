package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * A target could not be removed because backups of it still exist.
 *
 * <p>Deliberate: the backups are the valuable thing here, and deleting a target
 * must not be a way to lose them by accident. They are removed explicitly
 * first.
 */
public class TargetInUseException extends RuntimeException {

    public TargetInUseException(String name) {
        super("'%s' still has backups. Remove them before removing the target.".formatted(name));
    }
}
