package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidRetentionPolicyException;

import lombok.Builder;
import lombok.Getter;

/**
 * Automatic cleanup settings for one target, together with the latest sweep
 * outcome. Absence of this aggregate means retention is disabled.
 */
@Getter
public final class BackupRetentionPolicy {

    public static final int MAX_ERROR_LENGTH = 2000;

    private final UUID targetId;
    private final int keepSuccessful;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final Instant lastRunAt;
    private final Integer lastDeletedCount;
    private final String lastError;

    @Builder
    private BackupRetentionPolicy(
            UUID targetId,
            int keepSuccessful,
            Instant createdAt,
            Instant updatedAt,
            Instant lastRunAt,
            Integer lastDeletedCount,
            String lastError) {

        this.targetId = require(targetId, "targetId", "Target is required");
        if (keepSuccessful < 1) {
            throw new InvalidRetentionPolicyException(
                    "keepSuccessful", "Keep successful backups must be at least 1");
        }
        this.keepSuccessful = keepSuccessful;
        this.createdAt = require(createdAt, "createdAt", "Creation timestamp is required");
        this.updatedAt = require(updatedAt, "updatedAt", "Update timestamp is required");
        if (updatedAt.isBefore(createdAt)) {
            throw new InvalidRetentionPolicyException(
                    "updatedAt", "Update timestamp cannot precede creation");
        }
        if ((lastRunAt == null) != (lastDeletedCount == null)) {
            throw new InvalidRetentionPolicyException(
                    "lastRunAt", "A retention result needs both a timestamp and deleted count");
        }
        if (lastDeletedCount != null && lastDeletedCount < 0) {
            throw new InvalidRetentionPolicyException(
                    "lastDeletedCount", "Deleted count cannot be negative");
        }
        if (lastRunAt == null && lastError != null) {
            throw new InvalidRetentionPolicyException(
                    "lastError", "An error belongs to a completed retention run");
        }
        this.lastRunAt = lastRunAt;
        this.lastDeletedCount = lastDeletedCount;
        this.lastError = truncate(normalize(lastError));
    }

    public static BackupRetentionPolicy create(UUID targetId, int keepSuccessful, Instant now) {
        return BackupRetentionPolicy.builder()
                .targetId(targetId)
                .keepSuccessful(keepSuccessful)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /** Changing the rule resets its outcome until the new rule has run. */
    public BackupRetentionPolicy edited(int keepSuccessful, Instant now) {
        return BackupRetentionPolicy.builder()
                .targetId(targetId)
                .keepSuccessful(keepSuccessful)
                .createdAt(createdAt)
                .updatedAt(now)
                .build();
    }

    public BackupRetentionPolicy completed(int deletedCount, String error, Instant ranAt) {
        return BackupRetentionPolicy.builder()
                .targetId(targetId)
                .keepSuccessful(keepSuccessful)
                .createdAt(createdAt)
                .updatedAt(updatedAt)
                .lastRunAt(ranAt)
                .lastDeletedCount(deletedCount)
                .lastError(error)
                .build();
    }

    public boolean lastRunFailed() {
        return lastError != null;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String truncate(String value) {
        return value == null || value.length() <= MAX_ERROR_LENGTH
                ? value
                : value.substring(0, MAX_ERROR_LENGTH);
    }

    private static <T> T require(T value, String field, String message) {
        if (value == null) {
            throw new InvalidRetentionPolicyException(field, message);
        }
        return value;
    }
}
