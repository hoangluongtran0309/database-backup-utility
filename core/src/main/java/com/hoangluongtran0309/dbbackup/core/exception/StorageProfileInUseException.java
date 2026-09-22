package com.hoangluongtran0309.dbbackup.core.exception;

public final class StorageProfileInUseException extends RuntimeException {
    public StorageProfileInUseException(String name) {
        super("Storage profile '" + name + "' is still referenced by a target or backup artifact");
    }
}
