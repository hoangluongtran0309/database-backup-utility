package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

/**
 * One attempt to load a backup artifact into a target — the one it was taken
 * from, or any other registered target.
 *
 * <p>A separate aggregate from {@link BackupExecution} rather than a status on
 * it: a backup is a thing that exists, and restoring it is an event that can
 * happen to it many times, or never.
 *
 * <p>Immutable, and it owns its state machine for the same reason a backup
 * does — a result arriving late must not overwrite a recorded outcome.
 */
@Getter
public final class RestoreExecution {

    /** Matches the column width in V4. */
    public static final int MAX_ERROR_LENGTH = 2000;

    private final UUID id;

    /** The backup being restored. */
    private final UUID backupExecutionId;

    /**
     * Where the data went. Usually the target the backup was taken from, but
     * not necessarily: see ADR-014. Recorded rather than derived from the
     * backup, because the two can differ.
     */
    private final UUID targetId;

    private final ExecutionStatus status;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final String errorMessage;

    @Builder
    private RestoreExecution(
            UUID id,
            UUID backupExecutionId,
            UUID targetId,
            ExecutionStatus status,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage) {

        this.id = require(id, "Restore id is required");
        this.backupExecutionId = require(backupExecutionId, "Backup execution id is required");
        this.targetId = require(targetId, "Target id is required");
        this.status = require(status, "Status is required");
        this.startedAt = require(startedAt, "Start timestamp is required");
        this.finishedAt = finishedAt;
        this.errorMessage = truncate(errorMessage);

        if (status.isFinished() == (finishedAt == null)) {
            throw new IllegalArgumentException(
                    "A finished restore needs a finish timestamp, and a running one must not have "
                            + "(status=%s, finishedAt=%s)".formatted(status, finishedAt));
        }
    }

    /** @param targetId where the backup is going, which need not be where it came from */
    public static RestoreExecution started(UUID id, UUID backupExecutionId, UUID targetId, Instant startedAt) {
        return RestoreExecution.builder()
                .id(id)
                .backupExecutionId(backupExecutionId)
                .targetId(targetId)
                .status(ExecutionStatus.RUNNING)
                .startedAt(startedAt)
                .build();
    }

    public RestoreExecution succeeded(Instant finishedAt) {
        requireStillRunning();
        return RestoreExecution.builder()
                .id(id)
                .backupExecutionId(backupExecutionId)
                .targetId(targetId)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .build();
    }

    public RestoreExecution failed(String errorMessage, Instant finishedAt) {
        requireStillRunning();
        return RestoreExecution.builder()
                .id(id)
                .backupExecutionId(backupExecutionId)
                .targetId(targetId)
                .status(ExecutionStatus.FAILED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .errorMessage((errorMessage == null || errorMessage.isBlank())
                        ? "Restore failed, with no reason reported"
                        : errorMessage.strip())
                .build();
    }

    public Duration duration(Instant now) {
        return Duration.between(startedAt, finishedAt != null ? finishedAt : now);
    }

    private void requireStillRunning() {
        if (status.isFinished()) {
            throw new IllegalStateException(
                    "Restore %s already finished as %s".formatted(id, status));
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_ERROR_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_ERROR_LENGTH);
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
