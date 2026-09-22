package com.hoangluongtran0309.dbbackup.core.model;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;
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
    private static final int MAX_SQLITE_PATH_LENGTH = 1024;
    private static final int MAX_ORACLE_IDENTIFIER_LENGTH = 128;
    private static final int MAX_MARIADB_USERNAME_LENGTH = 128;
    private static final int MAX_SQLSERVER_IDENTIFIER_LENGTH = 128;

    private final UUID id;
    private final String name;
    private final DatabaseEngine engine;
    private final String host;
    private final Integer port;
    private final String databaseName;
    private final String username;
    private final String authenticationDatabase;
    private final String dataPumpDirectory;
    /** Null selects the built-in local filesystem destination. */
    private final UUID storageProfileId;

    /**
     * The target's password, AES-256-GCM encrypted, Base64 encoded.
     *
     * <p>For credential-bearing targets this field holds ciphertext at every
     * point in the object's life, and the name says so. A plaintext password
     * must never be assigned here: when a slice needs one, it belongs in a
     * separate short-lived type that is named for what it carries. SQLite
     * targets do not have credentials, so the field is {@code null} for them.
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
            Integer port,
            String databaseName,
            String username,
            String authenticationDatabase,
            String dataPumpDirectory,
            UUID storageProfileId,
            String passwordCiphertext,
            Instant createdAt,
            ConnectionCheck lastConnectionCheck) {

        this.id = require(id, "id", "Target id is required");
        this.name = text(name, "name", "Name", MAX_NAME_LENGTH);
        this.engine = require(engine, "engine", "Database engine is required");
        this.host = engine.isFileBased()
                ? absent(host, "host", "Host is not used by SQLite targets")
                : text(host, "host", "Host", MAX_HOST_LENGTH);
        this.port = engine.isFileBased()
                ? absent(port, "port", "Port is not used by SQLite targets")
                : port(port);
        this.databaseName = engine.isFileBased()
                ? sqlitePath(databaseName)
                : text(databaseName, "databaseName", "Database name", databaseNameLimit(engine));
        this.username = engine.isFileBased()
                ? absent(username, "username", "Username is not used by SQLite targets")
                : username(engine, username);
        this.authenticationDatabase = authenticationDatabase(engine, authenticationDatabase);
        this.dataPumpDirectory = dataPumpDirectory(engine, dataPumpDirectory);
        this.storageProfileId = storageProfileId;
        this.passwordCiphertext = engine.isFileBased()
                ? absent(passwordCiphertext, "passwordCiphertext", "Password is not used by SQLite targets")
                : require(passwordCiphertext, "passwordCiphertext", "Password is required");
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
            Integer port,
            String username,
            String authenticationDatabase,
            String dataPumpDirectory,
            String newPasswordCiphertext,
            UUID storageProfileId) {

        // Built before comparing, so the comparison sees the values as the
        // constructor normalises them — " db " and "db" are the same host.
        DatabaseTarget edited = toBuilder()
                .name(name)
                .host(host)
                .port(port)
                .username(username)
                .authenticationDatabase(authenticationDatabase)
                .dataPumpDirectory(dataPumpDirectory)
                .storageProfileId(storageProfileId)
                .passwordCiphertext(newPasswordCiphertext != null ? newPasswordCiphertext : passwordCiphertext)
                .build();

        boolean connectionChanged = newPasswordCiphertext != null
                || !Objects.equals(edited.host, this.host)
                || !Objects.equals(edited.port, this.port)
                || !Objects.equals(edited.username, this.username)
                || !Objects.equals(edited.authenticationDatabase, this.authenticationDatabase)
                || !Objects.equals(edited.dataPumpDirectory, this.dataPumpDirectory);
        return connectionChanged ? edited.toBuilder().lastConnectionCheck(null).build() : edited;
    }

    public DatabaseTarget edited(
            String name,
            String host,
            Integer port,
            String username,
            String authenticationDatabase,
            String dataPumpDirectory,
            String newPasswordCiphertext) {
        return edited(name, host, port, username, authenticationDatabase, dataPumpDirectory,
                newPasswordCiphertext, storageProfileId);
    }

    /** Existing SQL callers have no separate authentication database. */
    public DatabaseTarget edited(
            String name, String host, Integer port, String username, String newPasswordCiphertext) {
        return edited(name, host, port, username, authenticationDatabase, dataPumpDirectory,
                newPasswordCiphertext);
    }

    public DatabaseTarget edited(
            String name,
            String host,
            Integer port,
            String username,
            String authenticationDatabase,
            String newPasswordCiphertext) {
        return edited(name, host, port, username, authenticationDatabase, dataPumpDirectory,
                newPasswordCiphertext);
    }

    /** True once this target has been probed at least once, whatever the outcome. */
    public boolean hasBeenTested() {
        return lastConnectionCheck != null;
    }

    /** {@code host:port/schema} — how a target identifies itself in the console. */
    public String address() {
        if (engine.isFileBased()) {
            return databaseName;
        }
        return "%s:%d/%s".formatted(host, port, databaseName);
    }

    /** Safe basename used in the artifact filename for both schemas and files. */
    public String artifactBaseName() {
        return engine.isFileBased() ? Path.of(databaseName).getFileName().toString() : databaseName;
    }

    /** Namespace stored in a logical artifact and needed for same-engine remapping. */
    public String backupNamespace() {
        return engine == DatabaseEngine.ORACLE ? username : databaseName;
    }

    private static int databaseNameLimit(DatabaseEngine engine) {
        if (engine == DatabaseEngine.POSTGRESQL) {
            return 63;
        }
        if (engine == DatabaseEngine.ORACLE) {
            return 255;
        }
        return engine == DatabaseEngine.SQLSERVER
                ? MAX_SQLSERVER_IDENTIFIER_LENGTH
                : MAX_DATABASE_NAME_LENGTH;
    }

    private static int usernameLimit(DatabaseEngine engine) {
        if (engine == DatabaseEngine.MYSQL) {
            return 32;
        }
        if (engine == DatabaseEngine.ORACLE) {
            return MAX_ORACLE_IDENTIFIER_LENGTH;
        }
        if (engine == DatabaseEngine.MARIADB) {
            return MAX_MARIADB_USERNAME_LENGTH;
        }
        if (engine == DatabaseEngine.SQLSERVER) {
            return MAX_SQLSERVER_IDENTIFIER_LENGTH;
        }
        return 63;
    }

    private static String username(DatabaseEngine engine, String value) {
        String username = text(value, "username", "Username", usernameLimit(engine));
        return engine == DatabaseEngine.ORACLE
                ? oracleIdentifier(username, "username", "Oracle schema")
                : username;
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

    private static String dataPumpDirectory(DatabaseEngine engine, String value) {
        if (engine == DatabaseEngine.ORACLE) {
            return oracleIdentifier(
                    text(value, "dataPumpDirectory", "Data Pump directory", MAX_ORACLE_IDENTIFIER_LENGTH),
                    "dataPumpDirectory",
                    "Data Pump directory");
        }
        if (value != null && !value.isBlank()) {
            throw new InvalidTargetException(
                    "dataPumpDirectory", "Data Pump directory is only used by Oracle targets");
        }
        return null;
    }

    private static String oracleIdentifier(String value, String field, String label) {
        if (!value.matches("[A-Za-z][A-Za-z0-9_$#]*")) {
            throw new InvalidTargetException(
                    field, label + " must be an unquoted Oracle identifier");
        }
        return value.toUpperCase(java.util.Locale.ROOT);
    }

    private static String sqlitePath(String value) {
        String path = text(value, "databaseName", "Database file", MAX_SQLITE_PATH_LENGTH);
        if (path.contains("\\")) {
            throw new InvalidTargetException("databaseName", "Database file must use '/' as its separator");
        }
        try {
            Path parsed = Path.of(path);
            if (parsed.isAbsolute()) {
                throw new InvalidTargetException(
                        "databaseName", "Database file must be relative to SQLITE_ROOT");
            }
            for (Path part : parsed) {
                if (part.toString().equals(".") || part.toString().equals("..")) {
                    throw new InvalidTargetException(
                            "databaseName", "Database file must not contain '.' or '..' segments");
                }
            }
            return parsed.normalize().toString();
        } catch (InvalidPathException e) {
            throw new InvalidTargetException("databaseName", "Database file is not a valid path");
        }
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

    private static Integer port(Integer value) {
        if (value == null) {
            throw new InvalidTargetException("port", "Port is required");
        }
        if (value < 1 || value > 65535) {
            throw new InvalidTargetException("port", "Port must be between 1 and 65535");
        }
        return value;
    }

    private static String absent(String value, String field, String message) {
        if (value != null && !value.isBlank()) {
            throw new InvalidTargetException(field, message);
        }
        return null;
    }

    private static Integer absent(Integer value, String field, String message) {
        if (value != null) {
            throw new InvalidTargetException(field, message);
        }
        return null;
    }

    private static <T> T require(T value, String field, String message) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new InvalidTargetException(field, message);
        }
        return value;
    }
}
