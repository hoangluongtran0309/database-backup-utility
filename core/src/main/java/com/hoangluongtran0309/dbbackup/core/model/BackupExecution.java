package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

import lombok.Builder;
import lombok.Getter;

/**
 * One attempt to back up one target.
 *
 * <p>Immutable, and it owns its own state machine: {@link #succeeded} and
 * {@link #failed} return new instances and refuse to act on an execution that
 * has already finished. A late-arriving result therefore cannot quietly
 * overwrite a recorded outcome.
 */
@Getter
public final class BackupExecution {

    /** Matches the column width in V3. A stderr dump can be far longer. */
    public static final int MAX_ERROR_LENGTH = 2000;

    /** Lower-case hex, the form {@code sha256sum} prints — and the check in V5. */
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final UUID id;
    private final UUID targetId;
    private final ExecutionStatus status;
    private final Instant startedAt;

    /** Null while {@link ExecutionStatus#RUNNING}, set on every terminal state. */
    private final Instant finishedAt;

    /** Where the dump was written. Null unless the backup succeeded. */
    private final String artifactPath;

    /** Null unless the backup succeeded. */
    private final Long sizeBytes;

    /**
     * SHA-256 of the artifact exactly as stored — still gzipped — in lower-case
     * hex. Null unless the backup succeeded, and null for backups made before
     * checksums were recorded. See ADR-013.
     */
    private final String sha256;

    /** Null unless the backup failed. */
    private final String errorMessage;

    @Builder
    private BackupExecution(
            UUID id,
            UUID targetId,
            ExecutionStatus status,
            Instant startedAt,
            Instant finishedAt,
            String artifactPath,
            Long sizeBytes,
            String sha256,
            String errorMessage) {

        this.id = require(id, "Execution id is required");
        this.targetId = require(targetId, "Target id is required");
        this.status = require(status, "Status is required");
        this.startedAt = require(startedAt, "Start timestamp is required");
        this.finishedAt = finishedAt;
        this.artifactPath = artifactPath;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256;
        this.errorMessage = truncate(errorMessage);

        if (status.isFinished() == (finishedAt == null)) {
            throw new IllegalArgumentException(
                    "A finished execution needs a finish timestamp, and a running one must not have "
                            + "(status=%s, finishedAt=%s)".formatted(status, finishedAt));
        }
        if (sha256 != null && (status != ExecutionStatus.SUCCEEDED || !SHA256_HEX.matcher(sha256).matches())) {
            throw new IllegalArgumentException(
                    "A checksum belongs only to a successful backup, as 64 lower-case hex digits "
                            + "(status=%s, sha256=%s)".formatted(status, sha256));
        }
    }

    /** A backup that has just been accepted and not yet run. */
    public static BackupExecution started(UUID id, UUID targetId, Instant startedAt) {
        return BackupExecution.builder()
                .id(id)
                .targetId(targetId)
                .status(ExecutionStatus.RUNNING)
                .startedAt(startedAt)
                .build();
    }

    /**
     * @param sha256 the artifact's checksum, as {@code StoragePort#sha256Of}
     *        computed it once the file was closed. Required: every backup made
     *        from now on has one.
     */
    public BackupExecution succeeded(String artifactPath, long sizeBytes, String sha256, Instant finishedAt) {
        requireStillRunning();
        require(sha256, "A successful backup needs its checksum");
        return BackupExecution.builder()
                .id(id)
                .targetId(targetId)
                .status(ExecutionStatus.SUCCEEDED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                .artifactPath(artifactPath)
                .sizeBytes(sizeBytes)
                .sha256(sha256)
                .build();
    }

    /** False for backups made before checksums were recorded. */
    public boolean hasChecksum() {
        return sha256 != null;
    }

    public BackupExecution failed(String errorMessage, Instant finishedAt) {
        requireStillRunning();
        return BackupExecution.builder()
                .id(id)
                .targetId(targetId)
                .status(ExecutionStatus.FAILED)
                .startedAt(startedAt)
                .finishedAt(finishedAt)
                // A failure with nothing to say is worse than a house message.
                .errorMessage((errorMessage == null || errorMessage.isBlank())
                        ? "Backup failed, with no reason reported"
                        : errorMessage.strip())
                .build();
    }

    /** How long it ran, or has been running so far. */
    public Duration duration(Instant now) {
        return Duration.between(startedAt, finishedAt != null ? finishedAt : now);
    }

    private void requireStillRunning() {
        if (status.isFinished()) {
            throw new IllegalStateException(
                    "Execution %s already finished as %s".formatted(id, status));
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
