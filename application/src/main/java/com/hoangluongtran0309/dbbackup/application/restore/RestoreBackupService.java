package com.hoangluongtran0309.dbbackup.application.restore;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.MysqlConnection;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.MysqlLogicalRestorePort;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

import lombok.RequiredArgsConstructor;

/**
 * Loads a backup artifact back into the target it came from.
 *
 * <p>Split into accept-then-run for the same reasons as a backup, described in
 * ADR-004: the row is committed before the work is submitted, so an accepted
 * restore has a URL to poll and a crash leaves evidence.
 */
@Service
@RequiredArgsConstructor
public class RestoreBackupService {

    private static final Logger log = LoggerFactory.getLogger(RestoreBackupService.class);

    private final BackupExecutionRepository backups;
    private final RestoreExecutionRepository restores;
    private final DatabaseTargetRepository targets;
    private final MysqlLogicalRestorePort restoreEngine;
    private final StoragePort storage;
    private final EncryptionPort encryption;
    private final Executor jobExecutor;
    private final Clock clock;

    /**
     * Accepts a restore of {@code backupExecutionId} into its own target.
     *
     * <p>Not {@code @Transactional}, deliberately — see ADR-004.
     *
     * @throws NoSuchElementException if the backup, or the target it belongs
     *         to, no longer exists
     * @throws RestoreFailedException if the backup never produced an artifact
     */
    public UUID start(UUID backupExecutionId) {
        BackupExecution backup = backups.findById(backupExecutionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No backup execution with id " + backupExecutionId));

        // Checked before anything is persisted: a restore of a failed or
        // still-running backup is not a job that can be attempted.
        if (backup.getStatus() != ExecutionStatus.SUCCEEDED) {
            throw new RestoreFailedException(
                    "That backup is %s, so there is nothing to restore".formatted(backup.getStatus()));
        }

        DatabaseTarget target = targets.findById(backup.getTargetId())
                .orElseThrow(() -> new NoSuchElementException(
                        "The target this backup came from no longer exists"));

        RestoreExecution execution = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupExecutionId, clock.instant()));

        try {
            jobExecutor.execute(() -> run(execution.getId(), target, backup));
        } catch (RejectedExecutionException e) {
            finish(execution, "Too many jobs are already running or queued. Try again shortly.");
        }
        return execution.getId();
    }

    /** Runs one accepted restore. Called on a background thread; never throws. */
    void run(UUID restoreId, DatabaseTarget target, BackupExecution backup) {
        RestoreExecution execution = restores.findById(restoreId).orElseThrow();
        Path artifact = Path.of(backup.getArtifactPath());
        try {
            requireIntact(backup, artifact);

            MysqlConnection connection =
                    MysqlConnection.to(target, encryption.decrypt(target.getPasswordCiphertext()));

            restoreEngine.restore(connection, artifact);
            restores.save(execution.succeeded(clock.instant()));
            log.info("Restore {} of {} into target {} succeeded", restoreId, artifact, target.getName());

        } catch (RestoreFailedException e) {
            finish(execution, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Restore {} into target {} failed unexpectedly", restoreId, target.getName(), e);
            finish(execution, e.toString());
        }
    }

    /**
     * Checked on the job thread, just before the client is started, rather than
     * when the restore is accepted: the file could change in between, and the
     * check reads the whole of it. See ADR-013.
     *
     * <p>A backup made before checksums were recorded is restored unchecked, as
     * it always was.
     */
    private void requireIntact(BackupExecution backup, Path artifact) {
        if (!storage.exists(artifact)) {
            throw new RestoreFailedException(
                    "The backup artifact '%s' is no longer on disk. Nothing was restored.".formatted(artifact));
        }
        if (!backup.hasChecksum()) {
            return;
        }
        String actual = storage.sha256Of(artifact);
        if (!backup.getSha256().equals(actual)) {
            throw new RestoreFailedException((
                    "The backup artifact '%s' does not match the checksum recorded when it was made "
                            + "(recorded %s, now %s). It has changed since it was written, so it was not "
                            + "restored and the target was not touched.")
                    .formatted(artifact, backup.getSha256(), actual));
        }
    }

    /** @see com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository#findRunning() */
    public int failInterruptedRestores() {
        List<RestoreExecution> stranded = restores.findRunning();
        stranded.forEach(execution -> finish(
                execution, "Interrupted: the application stopped while this restore was running."));
        if (!stranded.isEmpty()) {
            log.warn("Marked {} restore(s) left running by a previous process as failed", stranded.size());
        }
        return stranded.size();
    }

    private void finish(RestoreExecution execution, String message) {
        restores.save(execution.failed(message, clock.instant()));
    }
}
