package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

/** One attempt to prove that a stored backup restores into an isolated database. */
@Getter
public final class RestoreVerificationExecution {

    public static final int MAX_MESSAGE_LENGTH = 2000;

    private final UUID id;
    private final UUID backupExecutionId;
    private final RestoreVerificationStatus status;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Integer checkedObjects;
    private final String resultSummary;
    private final String errorMessage;

    @Builder
    private RestoreVerificationExecution(
            UUID id,
            UUID backupExecutionId,
            RestoreVerificationStatus status,
            Instant startedAt,
            Instant finishedAt,
            Integer checkedObjects,
            String resultSummary,
            String errorMessage) {
        this.id = require(id, "Verification id is required");
        this.backupExecutionId = require(backupExecutionId, "Backup execution id is required");
        this.status = require(status, "Verification status is required");
        this.startedAt = require(startedAt, "Start timestamp is required");
        this.finishedAt = finishedAt;
        this.checkedObjects = checkedObjects;
        this.resultSummary = truncate(resultSummary);
        this.errorMessage = truncate(errorMessage);

        if (status.isFinished() == (finishedAt == null)) {
            throw new IllegalArgumentException("A finished verification needs a finish timestamp");
        }
        if (status == RestoreVerificationStatus.SUCCEEDED
                && (checkedObjects == null || checkedObjects < 0 || resultSummary == null || resultSummary.isBlank())) {
            throw new IllegalArgumentException("A successful verification needs its evidence");
        }
        if (status == RestoreVerificationStatus.FAILED
                && (errorMessage == null || errorMessage.isBlank())) {
            throw new IllegalArgumentException("A failed verification needs an error message");
        }
        if (status == RestoreVerificationStatus.RUNNING
                && (checkedObjects != null || resultSummary != null || errorMessage != null)) {
            throw new IllegalArgumentException("A running verification cannot have a result");
        }
    }

    public static RestoreVerificationExecution started(UUID id, UUID backupExecutionId, Instant startedAt) {
        return RestoreVerificationExecution.builder()
                .id(id)
                .backupExecutionId(backupExecutionId)
                .status(RestoreVerificationStatus.RUNNING)
                .startedAt(startedAt)
                .build();
    }

    public RestoreVerificationExecution succeeded(RestoreVerificationResult result, Instant finishedAt) {
        requireStillRunning();
        require(result, "Verification result is required");
        return RestoreVerificationExecution.builder()
                .id(id)
                .backupExecutionId(backupExecutionId)
                .status(RestoreVerificationStatus.SUCCEEDED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .checkedObjects(result.checkedObjects())
                .resultSummary(result.summary())
                .build();
    }

    public RestoreVerificationExecution failed(String message, Instant finishedAt) {
        requireStillRunning();
        String useful = message == null || message.isBlank()
                ? "Restore verification failed, with no reason reported"
                : message.strip();
        return RestoreVerificationExecution.builder()
                .id(id)
                .backupExecutionId(backupExecutionId)
                .status(RestoreVerificationStatus.FAILED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .errorMessage(useful)
                .build();
    }

    public Duration duration(Instant now) {
        return Duration.between(startedAt, finishedAt == null ? now : finishedAt);
    }

    private void requireStillRunning() {
        if (status.isFinished()) {
            throw new IllegalStateException("Verification %s already finished as %s".formatted(id, status));
        }
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_MESSAGE_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_MESSAGE_LENGTH);
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
