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

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

import lombok.RequiredArgsConstructor;

/**
 * Loads a backup artifact into a target: the one it came from, or any other
 * registered target (ADR-014).
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
    private final EngineAdapterRegistry adapters;
    private final StoragePort storage;
    private final EncryptionPort encryption;
    private final Executor jobExecutor;
    private final Clock clock;

    /**
     * Accepts a restore of {@code backupExecutionId} into {@code targetId}.
     *
     * <p>Not {@code @Transactional}, deliberately — see ADR-004.
     *
     * @param targetId where the data goes — the backup's own target, or any
     *        other same-engine target. SQL dumps are directed at the selected
     *        database; MongoDB archives are namespace-rewritten from the
     *        source database to it (ADR-018).
     * @throws NoSuchElementException if the backup or the target no longer exists
     * @throws RestoreFailedException if the backup never produced an artifact
     */
    public UUID start(UUID backupExecutionId, UUID targetId) {
        BackupExecution backup = backups.findById(backupExecutionId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No backup execution with id " + backupExecutionId));

        // Checked before anything is persisted: a restore of a failed or
        // still-running backup is not a job that can be attempted.
        if (backup.getStatus() != ExecutionStatus.SUCCEEDED) {
            throw new RestoreFailedException(
                    "That backup is %s, so there is nothing to restore".formatted(backup.getStatus()));
        }

        DatabaseTarget target = targets.findById(targetId)
                .orElseThrow(() -> new NoSuchElementException("The target to restore into no longer exists"));
        DatabaseTarget source = targets.findById(backup.getTargetId())
                .orElseThrow(() -> new NoSuchElementException("The target this backup came from no longer exists"));
        if (source.getEngine() != target.getEngine()) {
            throw new RestoreFailedException(
                    "A %s backup can only be restored into a %s target"
                            .formatted(source.getEngine().displayName(), source.getEngine().displayName()));
        }

        RestoreExecution execution = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupExecutionId, targetId, clock.instant()));

        try {
            jobExecutor.execute(() -> run(execution.getId(), source.getDatabaseName(), target, backup));
        } catch (RejectedExecutionException e) {
            finish(execution, "Too many jobs are already running or queued. Try again shortly.");
        }
        return execution.getId();
    }

    /** Runs one accepted restore. Called on a background thread; never throws. */
    void run(UUID restoreId, String sourceDatabase, DatabaseTarget target, BackupExecution backup) {
        RestoreExecution execution = restores.findById(restoreId).orElseThrow();
        Path artifact = Path.of(backup.getArtifactPath());
        try {
            requireIntact(backup, artifact);

            DatabaseConnection connection = DatabaseConnection.to(target, target.getPasswordCiphertext() == null
                    ? null
                    : encryption.decrypt(target.getPasswordCiphertext()));

            adapters.restoreFor(target.getEngine()).restore(connection, sourceDatabase, artifact);
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
