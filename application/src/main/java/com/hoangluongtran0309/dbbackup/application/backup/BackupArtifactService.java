package com.hoangluongtran0309.dbbackup.application.backup;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

import lombok.RequiredArgsConstructor;

/**
 * Getting a backup artifact out of the tool, checking it is still what was
 * written, and getting rid of it.
 */
@Service
@RequiredArgsConstructor
public class BackupArtifactService {

    private static final Logger log = LoggerFactory.getLogger(BackupArtifactService.class);

    /** An open stream over an artifact. The caller closes it. */
    public record ArtifactDownload(String filename, InputStream content) {
    }

    /** What deleting a backup would take with it. */
    public record DeletionPreview(BackupExecution execution, boolean artifactPresent, long restoreCount) {
    }

    /** What a verification found. */
    public enum Integrity {
        /** The file on disk has the checksum recorded when it was written. */
        INTACT,
        /** It does not: the file has changed since. */
        MISMATCH,
        /** The file is gone. */
        MISSING,
        /** The backup predates checksums, so there is nothing to compare with. */
        NOT_RECORDED
    }

    /**
     * @param recorded the checksum stored with the backup, or null if none was
     * @param actual   the checksum of the file as it is now, or null if missing
     */
    public record Verification(Integrity integrity, String recorded, String actual) {
    }

    private final BackupExecutionRepository backups;
    private final RestoreExecutionRepository restores;
    private final StoragePort storage;

    /**
     * @throws NoSuchElementException if the backup, or its artifact, is not there
     */
    public ArtifactDownload download(UUID executionId) {
        BackupExecution execution = require(executionId);
        Path artifact = artifactOf(execution);

        if (!storage.exists(artifact)) {
            // The row outlives the file if someone removed it from underneath
            // us. Saying so beats a stack trace or an empty download.
            throw new NoSuchElementException(
                    "The artifact for this backup is no longer on disk: " + artifact);
        }
        return new ArtifactDownload(artifact.getFileName().toString(), storage.openForReading(artifact));
    }

    /**
     * Re-reads the artifact and compares it with the checksum recorded when it
     * was written. Reads the whole file, so it takes as long as the disk does.
     *
     * <p>A backup without a recorded checksum still has its file read: the
     * checksum it has now is worth showing, to compare against a copy kept
     * elsewhere.
     *
     * @throws NoSuchElementException if the backup is not there, or never
     *         produced an artifact
     */
    public Verification verify(UUID executionId) {
        BackupExecution execution = require(executionId);
        Path artifact = artifactOf(execution);

        if (!storage.exists(artifact)) {
            return new Verification(Integrity.MISSING, execution.getSha256(), null);
        }
        String actual = storage.sha256Of(artifact);
        if (!execution.hasChecksum()) {
            return new Verification(Integrity.NOT_RECORDED, null, actual);
        }
        return new Verification(
                execution.getSha256().equals(actual) ? Integrity.INTACT : Integrity.MISMATCH,
                execution.getSha256(),
                actual);
    }

    /** Whether a backup's artifact is still on disk; false if it never had one. */
    public boolean isOnDisk(BackupExecution execution) {
        return execution.getArtifactPath() != null && storage.exists(Path.of(execution.getArtifactPath()));
    }

    public DeletionPreview previewDeletion(UUID executionId) {
        BackupExecution execution = require(executionId);
        return new DeletionPreview(execution, isOnDisk(execution), restores.countForBackup(executionId));
    }

    /**
     * Removes a backup: its restore records, then its file, then its row.
     *
     * <p>That order is deliberate. Deleting the row first and then failing to
     * delete the file would leave an artifact on disk with nothing in the
     * database pointing at it — invisible, and impossible to find later except
     * by hand. This way a failure leaves a visible row whose file is already
     * gone, which the operator can simply delete again.
     *
     * @return whether the backup had an artifact — false for one that failed
     *         before producing a file, so that only its record went
     * @throws IllegalStateException if the backup is still running
     */
    public boolean delete(UUID executionId) {
        BackupExecution execution = require(executionId);
        if (execution.getStatus() == ExecutionStatus.RUNNING) {
            throw new IllegalStateException(
                    "This backup is still running. Wait for it to finish before deleting it.");
        }

        boolean hadArtifact = execution.getArtifactPath() != null;
        restores.deleteForBackup(executionId);
        if (hadArtifact) {
            storage.delete(Path.of(execution.getArtifactPath()));
        }
        backups.deleteById(executionId);
        log.info("Deleted backup {} and its artifact {}", executionId, execution.getArtifactPath());
        return hadArtifact;
    }

    private BackupExecution require(UUID executionId) {
        return backups.findById(executionId)
                .orElseThrow(() -> new NoSuchElementException("No backup execution with id " + executionId));
    }

    private static Path artifactOf(BackupExecution execution) {
        if (execution.getArtifactPath() == null) {
            throw new NoSuchElementException(
                    "That backup produced no artifact — it is %s".formatted(execution.getStatus()));
        }
        return Path.of(execution.getArtifactPath());
    }
}
