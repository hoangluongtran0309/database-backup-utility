package com.hoangluongtran0309.dbbackup.application.backup;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.application.retention.ApplyBackupRetentionService;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.port.StoragePort;

import lombok.RequiredArgsConstructor;

/**
 * Starts backups and runs them.
 *
 * <p>The two halves are deliberately separate. {@link #start} validates,
 * persists a RUNNING row and returns — so an accepted backup has a stable URL
 * before any work begins, and a crash leaves an inspectable row rather than a
 * lost in-memory task. {@link #run} is what the background thread executes.
 */
@Service
@RequiredArgsConstructor
public class RunBackupService {

    private static final Logger log = LoggerFactory.getLogger(RunBackupService.class);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final DatabaseTargetRepository targets;
    private final BackupExecutionRepository executions;
    private final EngineAdapterRegistry adapters;
    private final StoragePort storage;
    private final EncryptionPort encryption;
    private final Executor jobExecutor;
    private final Clock clock;
    private final ApplyBackupRetentionService retention;

    /**
     * Accepts a backup and hands it to the background.
     *
     * <p><strong>Not {@code @Transactional}, and that is the point.</strong> The
     * RUNNING row must be committed before the background thread can load it;
     * inside a transaction the work would be submitted against a row no other
     * connection can see yet.
     *
     * @return the id of the persisted execution, ready to be polled
     * @throws NoSuchElementException if no target holds this id
     */
    public UUID start(UUID targetId) {
        DatabaseTarget target = targets.findById(targetId)
                .orElseThrow(() -> new NoSuchElementException("No database target with id " + targetId));
        LogicalBackupPort backupEngine = adapters.backupFor(target.getEngine());

        BackupExecution execution = executions.save(
                BackupExecution.started(UUID.randomUUID(), targetId, clock.instant()));

        try {
            jobExecutor.execute(() -> run(execution.getId(), target, backupEngine));
        } catch (RejectedExecutionException e) {
            // The queue is full. Saying so beats a row that sits at RUNNING
            // forever because nothing ever picked it up.
            finish(execution, "Too many jobs are already running or queued. Try again shortly.");
        }
        return execution.getId();
    }

    /** Runs one accepted backup. Called on a background thread; never throws. */
    void run(UUID executionId, DatabaseTarget target, LogicalBackupPort backupEngine) {
        BackupExecution execution = executions.findById(executionId).orElseThrow();
        Path destination = storage.locationFor(
                artifactFileName(target, execution.getStartedAt(), backupEngine.artifactSuffix()));

        try {
            // Decrypted here, at the last moment and on the thread that uses
            // it, rather than being carried through the queue.
            DatabaseConnection connection = connectionTo(target);

            long sizeBytes = backupEngine.dumpTo(connection, destination, executionId);
            // Read back from disk once the engine has closed the file, so the
            // checksum describes what was stored rather than what was sent.
            String sha256 = storage.sha256Of(destination);
            executions.save(execution.succeeded(destination.toString(), sizeBytes, sha256, clock.instant()));
            log.info("Backup {} of target {} succeeded: {} ({} bytes, sha256 {})",
                    executionId, target.getName(), destination, sizeBytes, sha256);

        } catch (BackupFailedException e) {
            finish(execution, e.getMessage());
            return;
        } catch (RuntimeException e) {
            // Anything unexpected still has to land on the row, or the console
            // shows RUNNING for a job that has already died.
            log.error("Backup {} of target {} failed unexpectedly", executionId, target.getName(), e);
            storage.delete(destination);
            finish(execution, e.toString());
            return;
        }

        // Retention begins only after the successful row is durable. It has a
        // separate failure boundary: cleanup can fail, but the backup did not.
        try {
            retention.applyAfterSuccessfulBackup(target.getId());
        } catch (RuntimeException e) {
            log.error("Could not apply automatic retention after backup {}", executionId, e);
        }
    }

    /**
     * Marks every execution left RUNNING by a previous process as failed.
     *
     * <p>Local client processes disappear with the application. Oracle Data
     * Pump work is server-side and can survive it, so the adapter cleanup hook
     * attaches to that stable execution job before the row is marked failed.
     */
    public int failInterruptedBackups() {
        List<BackupExecution> stranded = executions.findRunning();
        stranded.forEach(execution -> {
            String message = "Interrupted: the application stopped while this backup was running.";
            DatabaseTarget target;
            LogicalBackupPort adapter;
            try {
                target = targets.findById(execution.getTargetId()).orElseThrow();
                adapter = adapters.backupFor(target.getEngine());
            } catch (RuntimeException e) {
                message += " External engine cleanup was not confirmed: " + e.getMessage();
                log.error("Could not clean up interrupted backup {}", execution.getId(), e);
                finish(execution, message);
                return;
            }
            try {
                adapter.abortInterrupted(execution.getId(), () -> connectionTo(target));
            } catch (RuntimeException e) {
                message += " External engine cleanup was not confirmed: " + e.getMessage();
                log.error("Could not stop external work for interrupted backup {}", execution.getId(), e);
            }
            try {
                Path partial = storage.locationFor(
                        artifactFileName(target, execution.getStartedAt(), adapter.artifactSuffix()));
                storage.delete(partial);
            } catch (RuntimeException e) {
                message += " Partial artifact cleanup failed: " + e.getMessage();
                log.error("Could not remove partial artifact for interrupted backup {}", execution.getId(), e);
            }
            finish(execution, message);
        });
        if (!stranded.isEmpty()) {
            log.warn("Marked {} backup(s) left running by a previous process as failed", stranded.size());
        }
        return stranded.size();
    }

    private void finish(BackupExecution execution, String message) {
        executions.save(execution.failed(message, clock.instant()));
    }

    private DatabaseConnection connectionTo(DatabaseTarget target) {
        return DatabaseConnection.to(target, target.getPasswordCiphertext() == null
                ? null
                : encryption.decrypt(target.getPasswordCiphertext()));
    }

    /**
     * {@code shop_20260909_075300.sql.gz}. The database name is sanitised because
     * it comes from user input and is about to become a file name.
     */
    static String artifactFileName(DatabaseTarget target, Instant startedAt, String suffix) {
        if (suffix == null || !suffix.matches("\\.[a-zA-Z0-9.]+")) {
            throw new IllegalArgumentException(
                    "Artifact suffix must begin with a dot and contain only letters, digits or dots");
        }
        String schema = target.artifactBaseName().replaceAll("[^a-zA-Z0-9._-]", "_");
        return "%s_%s%s".formatted(schema, TIMESTAMP.format(startedAt), suffix);
    }
}
