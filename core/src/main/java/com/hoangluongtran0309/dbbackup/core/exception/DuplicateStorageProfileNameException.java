package com.hoangluongtran0309.dbbackup.core.exception;

public final class DuplicateStorageProfileNameException extends RuntimeException {
    public DuplicateStorageProfileNameException(String name) {
        super("A storage profile named '" + name + "' already exists");
    }
}
