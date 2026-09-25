package com.hoangluongtran0309.dbbackup.application.target;

import java.util.UUID;

/**
 * New connection details for an existing target, with the password — if there
 * is one — still in plain text.
 *
 * <p>No schema: a target's schema is fixed when it is registered. See
 * {@link com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget#edited}.
 *
 * @param password the new password, or {@code null} or blank to keep the
 *        current one. Blank means "unchanged" rather than "empty" because a
 *        password field is never pre-filled: an operator who only renames a
 *        target leaves it empty.
 */
public record EditTargetCommand(
        String name,
        String host,
        Integer port,
        String username,
        String password,
        String authenticationDatabase,
        String dataPumpDirectory,
        UUID storageProfileId,
        boolean verifyAfterBackup) {

    public EditTargetCommand(
            String name,
            String host,
            Integer port,
            String username,
            String password,
            String authenticationDatabase) {
        this(name, host, port, username, password, authenticationDatabase, null, null, false);
    }

    public EditTargetCommand(String name, String host, Integer port, String username, String password) {
        this(name, host, port, username, password, null, null, null, false);
    }

    public EditTargetCommand(
            String name, String host, Integer port, String username, String password,
            String authenticationDatabase, String dataPumpDirectory) {
        this(name, host, port, username, password, authenticationDatabase, dataPumpDirectory, null, false);
    }

    public boolean changesPassword() {
        return password != null && !password.isBlank();
    }

}
