package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Instant;

/**
 * The outcome of the last attempt to reach a target, as remembered.
 *
 * <p>The message on a failure is MySQL's own words, not a rewritten summary:
 * "Access denied for user" and "Unknown database" send an operator to two
 * completely different places, and a house-style "Connection failed" tells
 * them neither.
 */
public record ConnectionCheck(boolean successful, String message, Instant checkedAt) {

    /** Matches the column width in V2. MySQL errors can be long; the column is not. */
    public static final int MAX_MESSAGE_LENGTH = 500;

    public ConnectionCheck {
        if (checkedAt == null) {
            throw new IllegalArgumentException("checkedAt is required");
        }
        message = truncate(message);
    }

    public static ConnectionCheck passed(Instant checkedAt) {
        return new ConnectionCheck(true, "Connected", checkedAt);
    }

    public static ConnectionCheck failed(String message, Instant checkedAt) {
        // A failure with nothing to say is the one case where a house message
        // is better than an empty box in the console.
        String reported = (message == null || message.isBlank())
                ? "Connection failed, with no reason reported"
                : message.strip();
        return new ConnectionCheck(false, reported, checkedAt);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= MAX_MESSAGE_LENGTH
                ? value
                : value.substring(0, MAX_MESSAGE_LENGTH);
    }
}
