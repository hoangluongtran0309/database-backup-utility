package com.hoangluongtran0309.dbbackup.application.target;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;

/**
 * What the operator typed, with the password still in plain text.
 *
 * <p>Short-lived by design: {@link ManageDatabaseTargetService} encrypts the
 * password on the way into the domain model and this record is then dropped.
 */
public record RegisterTargetCommand(
        String name,
        String host,
        int port,
        String database,
        String username,
        String password) {

    public RegisterTargetCommand {
        // Every other field is checked by DatabaseTarget's constructor. The
        // password cannot be: by the time it reaches the model it is
        // ciphertext, and the ciphertext of an empty string is not empty.
        if (password == null || password.isBlank()) {
            throw new InvalidTargetException("password", "Password is required");
        }
    }
}
