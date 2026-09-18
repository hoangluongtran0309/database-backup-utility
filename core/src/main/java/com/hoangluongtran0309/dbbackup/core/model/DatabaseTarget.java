package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;

import lombok.Builder;
import lombok.Getter;

/**
 * A database and schema this tool is allowed to back up.
 *
 * <p>Immutable, and it refuses to exist in an invalid state: every constraint
 * is checked in the constructor, so a reference to a {@code DatabaseTarget}
 * is already proof that its values are sane. Callers do not validate first.
 *
 */
@Getter
public final class DatabaseTarget {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_HOST_LENGTH = 255;
    private static final int MAX_DATABASE_NAME_LENGTH = 64;

    private final UUID id;
    private final String name;
    private final DatabaseEngine engine;
    private final String host;
    private final int port;
    private final String databaseName;
    private final String username;
    private final String authenticationDatabase;

    /**
     * The target's password, AES-256-GCM encrypted, Base64 encoded.
     *
     * <p>This field holds ciphertext at every point in the object's life, and
     * the name says so. A plaintext password must never be assigned here:
     * when a slice needs one, it belongs in a separate short-lived type that
     * is named for what it carries.
     */
    private final String passwordCiphertext;

    private final Instant createdAt;

    /**
     * The last connection probe's outcome, or {@code null} if this target has
     * never been tested. Recorded through
     * {@link com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository#recordConnectionCheck}
     * rather than by saving the whole target, so a probe can never rewrite the
     * stored password.
     */
    private final ConnectionCheck lastConnectionCheck;

    @Builder(toBuilder = true)
    private DatabaseTarget(
            UUID id,
            String name,
            DatabaseEngine engine,
            String host,
            int port,
            String databaseName,
            String username,
            String authenticationDatabase,
            String passwordCiphertext,
            Instant createdAt,
            ConnectionCheck lastConnectionCheck) {

        this.id = require(id, "id", "Target id is required");
        this.name = text(name, "name", "Name", MAX_NAME_LENGTH);
        this.engine = require(engine, "engine", "Database engine is required");
        this.host = text(host, "host", "Host", MAX_HOST_LENGTH);
        this.port = port(port);
        this.databaseName = text(databaseName, "databaseName", "Database name", databaseNameLimit(engine));
        this.username = text(username, "username", "Username", usernameLimit(engine));
        this.authenticationDatabase = authenticationDatabase(engine, authenticationDatabase);
        this.passwordCiphertext = require(passwordCiphertext, "passwordCiphertext", "Password is required");
        this.createdAt = require(createdAt, "createdAt", "Creation timestamp is required");
        this.lastConnectionCheck = lastConnectionCheck; // absent until the target is first tested
    }

    /**
     * This target with new connection details — the same target, not a new one.
     *
     * <p>The id, the schema and the creation time stay. The schema above all:
     * every backup of this target is a dump of that schema, and a target that
     * could be pointed at another one would make its history describe a
     * database it no longer names. A different schema is a different target.
     *
     * <p>The last connection check is dropped when anything it depended on
     * changes, since it describes a connection that is no longer the one held.
     * A rename alone keeps it.
     *
     * @param newPasswordCiphertext the new password, already encrypted, or
     *        {@code null} to keep the current one
     * @throws InvalidTargetException if a value is missing or out of range
     */
    public DatabaseTarget edited(
            String name,
            String host,
            int port,
            String username,
            String authenticationDatabase,
            String newPasswordCiphertext) {

        // Built before comparing, so the comparison sees the values as the
        // constructor normalises them — " db " and "db" are the same host.
        DatabaseTarget edited = toBuilder()
                .name(name)
                .host(host)
                .port(port)
                .username(username)
                .authenticationDatabase(authenticationDatabase)
                .passwordCiphertext(newPasswordCiphertext != null ? newPasswordCiphertext : passwordCiphertext)
                .build();

        boolean connectionChanged = newPasswordCiphertext != null
                || !edited.host.equals(this.host)
                || edited.port != this.port
                || !edited.username.equals(this.username)
                || !java.util.Objects.equals(edited.authenticationDatabase, this.authenticationDatabase);
        return connectionChanged ? edited.toBuilder().lastConnectionCheck(null).build() : edited;
    }

    /** Existing SQL callers have no separate authentication database. */
    public DatabaseTarget edited(
            String name, String host, int port, String username, String newPasswordCiphertext) {
        return edited(name, host, port, username, authenticationDatabase, newPasswordCiphertext);
    }

    /** True once this target has been probed at least once, whatever the outcome. */
    public boolean hasBeenTested() {
        return lastConnectionCheck != null;
    }

    /** {@code host:port/schema} — how a target identifies itself in the console. */
    public String address() {
        return "%s:%d/%s".formatted(host, port, databaseName);
    }

    private static int databaseNameLimit(DatabaseEngine engine) {
        return engine == DatabaseEngine.POSTGRESQL ? 63 : MAX_DATABASE_NAME_LENGTH;
    }

    private static int usernameLimit(DatabaseEngine engine) {
        return engine == DatabaseEngine.MYSQL ? 32 : 63;
    }

    private static String authenticationDatabase(DatabaseEngine engine, String value) {
        if (engine == DatabaseEngine.MONGODB) {
            return text(value, "authenticationDatabase", "Authentication database", MAX_DATABASE_NAME_LENGTH);
        }
        if (value != null && !value.isBlank()) {
            throw new InvalidTargetException(
                    "authenticationDatabase", "Authentication database is only used by MongoDB targets");
        }
        return null;
    }

    private static String text(String value, String field, String label, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidTargetException(field, label + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxLength) {
            throw new InvalidTargetException(
                    field, "%s must be at most %d characters".formatted(label, maxLength));
        }
        return trimmed;
    }

    private static int port(int value) {
        if (value < 1 || value > 65535) {
            throw new InvalidTargetException("port", "Port must be between 1 and 65535");
        }
        return value;
    }

    private static <T> T require(T value, String field, String message) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new InvalidTargetException(field, message);
        }
        return value;
    }
}
