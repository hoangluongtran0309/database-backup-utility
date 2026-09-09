package com.hoangluongtran0309.dbbackup.core.exception;

/**
 * Two targets would share a name, compared case-insensitively.
 *
 * <p>Raised by the repository adapter when the database rejects the write, not
 * by a read-then-write check in a use case: only the unique index can decide
 * this without a race between two concurrent registrations.
 */
public class DuplicateTargetNameException extends RuntimeException {

    private final String name;

    public DuplicateTargetNameException(String name) {
        super("A database target named '%s' already exists".formatted(name));
        this.name = name;
    }

    public String getName() {
        return name;
    }
}
