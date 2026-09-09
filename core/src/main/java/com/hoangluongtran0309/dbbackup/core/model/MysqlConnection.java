package com.hoangluongtran0309.dbbackup.core.model;

/**
 * Everything the MySQL client binaries need to reach a target, with the
 * password in <strong>plain text</strong>.
 *
 * <p>This type exists so that no field named for ciphertext ever quietly holds
 * a plaintext password. {@link DatabaseTarget#getPasswordCiphertext()} is
 * encrypted at every moment of its life; a use case decrypts into one of these,
 * hands it to an adapter, and drops it.
 *
 * <p>An adapter given one of these must pass the password through
 * {@code ProcessBuilder.environment()} as {@code MYSQL_PWD} — never on the
 * command line, where {@code ps} would show it to every user on the host, and
 * never through {@code System.setProperty}, which is process-global and would
 * leak between jobs running at the same time.
 */
public record MysqlConnection(
        String host,
        int port,
        String database,
        String username,
        String password) {

    /** The connection details for a target, given its decrypted password. */
    public static MysqlConnection to(DatabaseTarget target, String plainPassword) {
        return new MysqlConnection(
                target.getHost(),
                target.getPort(),
                target.getDatabaseName(),
                target.getUsername(),
                plainPassword);
    }

    /**
     * Never let a password reach a log or a stack trace through a careless
     * string interpolation of this record.
     */
    @Override
    public String toString() {
        return "MysqlConnection[%s:%d/%s as %s]".formatted(host, port, database, username);
    }
}
