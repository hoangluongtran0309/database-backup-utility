package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;

import lombok.Builder;
import lombok.Getter;

/**
 * A MySQL server and schema this tool is allowed to back up.
 *
 * <p>Immutable, and it refuses to exist in an invalid state: every constraint
 * is checked in the constructor, so a reference to a {@code DatabaseTarget}
 * is already proof that its values are sane. Callers do not validate first.
 *
 * <p>There is no {@code engine} field. MySQL is the only engine, so a column
 * or an enum naming it would carry no information today; the migration that
 * introduces a second engine is the one that should add it.
 */
@Getter
public final class DatabaseTarget {

    private static final int MAX_NAME_LENGTH = 100;
    private static final int MAX_HOST_LENGTH = 255;

    /** MySQL's own limit on a schema identifier. */
    private static final int MAX_DATABASE_NAME_LENGTH = 64;

    /** MySQL 8's limit on a user name. */
    private static final int MAX_USERNAME_LENGTH = 32;

    private final UUID id;
    private final String name;
    private final String host;
    private final int port;
    private final String databaseName;
    private final String username;

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

    @Builder
    private DatabaseTarget(
            UUID id,
            String name,
            String host,
            int port,
            String databaseName,
            String username,
            String passwordCiphertext,
            Instant createdAt) {

        this.id = require(id, "id", "Target id is required");
        this.name = text(name, "name", "Name", MAX_NAME_LENGTH);
        this.host = text(host, "host", "Host", MAX_HOST_LENGTH);
        this.port = port(port);
        this.databaseName = text(databaseName, "databaseName", "Database name", MAX_DATABASE_NAME_LENGTH);
        this.username = text(username, "username", "Username", MAX_USERNAME_LENGTH);
        this.passwordCiphertext = require(passwordCiphertext, "passwordCiphertext", "Password is required");
        this.createdAt = require(createdAt, "createdAt", "Creation timestamp is required");
    }

    /** {@code host:port/schema} — how a target identifies itself in the console. */
    public String address() {
        return "%s:%d/%s".formatted(host, port, databaseName);
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
