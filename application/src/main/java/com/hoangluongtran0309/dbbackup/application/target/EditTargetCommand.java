package com.hoangluongtran0309.dbbackup.application.target;

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
        int port,
        String username,
        String password) {

    public boolean changesPassword() {
        return password != null && !password.isBlank();
    }
}
