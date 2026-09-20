package com.hoangluongtran0309.dbbackup.application.target;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

/**
 * What the operator typed, with the password still in plain text.
 *
 * <p>Short-lived by design: {@link ManageDatabaseTargetService} encrypts the
 * password on the way into the domain model and this record is then dropped.
 */
public record RegisterTargetCommand(
        String name,
        DatabaseEngine engine,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        String authenticationDatabase,
        String dataPumpDirectory) {

    public RegisterTargetCommand(
            String name,
            DatabaseEngine engine,
            String host,
            Integer port,
            String database,
            String username,
            String password,
            String authenticationDatabase) {
        this(name, engine, host, port, database, username, password, authenticationDatabase, null);
    }

    public RegisterTargetCommand(
            String name,
            DatabaseEngine engine,
            String host,
            Integer port,
            String database,
            String username,
            String password) {
        this(name, engine, host, port, database, username, password, null, null);
    }

    public RegisterTargetCommand {
        if (engine == null) {
            throw new InvalidTargetException("engine", "Database engine is required");
        }
        // Every other field is checked by DatabaseTarget's constructor. The
        // password cannot be: by the time it reaches the model it is
        // ciphertext, and the ciphertext of an empty string is not empty.
        if (!engine.isFileBased() && (password == null || password.isBlank())) {
            throw new InvalidTargetException("password", "Password is required");
        }
        if (engine.isFileBased() && password != null && !password.isBlank()) {
            throw new InvalidTargetException("password", "Password is not used by SQLite targets");
        }
        if (engine == DatabaseEngine.ORACLE && password != null
                && (password.indexOf('\n') >= 0 || password.indexOf('\r') >= 0)) {
            throw new InvalidTargetException("password", "Oracle password must not contain line breaks");
        }
    }
}
