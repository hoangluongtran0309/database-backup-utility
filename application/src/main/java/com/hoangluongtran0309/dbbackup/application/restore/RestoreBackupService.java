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
            jobExecutor.execute(() -> run(execution.getId(), target, Path.of(backup.getArtifactPath())));
        } catch (RejectedExecutionException e) {
            finish(execution, "Too many jobs are already running or queued. Try again shortly.");
        }
        return execution.getId();
    }

    /** Runs one accepted restore. Called on a background thread; never throws. */
    void run(UUID restoreId, DatabaseTarget target, Path artifact) {
        RestoreExecution execution = restores.findById(restoreId).orElseThrow();
        try {
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
