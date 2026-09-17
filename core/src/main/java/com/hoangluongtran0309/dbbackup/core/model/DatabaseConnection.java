package com.hoangluongtran0309.dbbackup.core.model;

/**
 * Short-lived, decrypted connection details handed from the application layer
 * to one engine adapter. Passwords must be passed to child processes through
 * their environment, never through command-line arguments or logs.
 */
public record DatabaseConnection(
        DatabaseEngine engine,
        String host,
        int port,
        String database,
        String username,
        String password) {

    public static DatabaseConnection to(DatabaseTarget target, String plainPassword) {
        return new DatabaseConnection(
                target.getEngine(),
                target.getHost(),
                target.getPort(),
                target.getDatabaseName(),
                target.getUsername(),
                plainPassword);
    }

    @Override
    public String toString() {
        return "DatabaseConnection[%s %s:%d/%s as %s]"
                .formatted(engine, host, port, database, username);
    }
}
