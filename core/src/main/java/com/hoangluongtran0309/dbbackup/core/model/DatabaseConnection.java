package com.hoangluongtran0309.dbbackup.core.model;

/**
 * Short-lived, decrypted connection details handed from the application layer
 * to one engine adapter. Passwords must reach child processes through an
 * engine-specific protected channel (an environment variable or owner-only
 * config file), never through command-line arguments or logs.
 */
public record DatabaseConnection(
        DatabaseEngine engine,
        String host,
        Integer port,
        String database,
        String username,
        String password,
        String authenticationDatabase) {

    /** Convenience for engines whose credentials belong to the target database itself. */
    public DatabaseConnection(
            DatabaseEngine engine,
            String host,
            Integer port,
            String database,
            String username,
            String password) {
        this(engine, host, port, database, username, password, null);
    }

    public static DatabaseConnection to(DatabaseTarget target, String plainPassword) {
        return new DatabaseConnection(
                target.getEngine(),
                target.getHost(),
                target.getPort(),
                target.getDatabaseName(),
                target.getUsername(),
                plainPassword,
                target.getAuthenticationDatabase());
    }

    @Override
    public String toString() {
        if (engine.isFileBased()) {
            return "DatabaseConnection[%s %s]".formatted(engine, database);
        }
        return "DatabaseConnection[%s %s:%d/%s as %s]"
                .formatted(engine, host, port, database, username);
    }
}
