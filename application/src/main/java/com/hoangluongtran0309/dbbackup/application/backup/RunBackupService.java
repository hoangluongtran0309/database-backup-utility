package com.hoangluongtran0309.dbbackup.application.backup;

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
import org.springframework.beans.factory.annotation.Autowired;

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.application.notification.NotificationDispatcher;
import com.hoangluongtran0309.dbbackup.application.retention.ApplyBackupRetentionService;
import com.hoangluongtran0309.dbbackup.application.storage.ArtifactStorageService;
import com.hoangluongtran0309.dbbackup.application.storage.ArtifactStorageService.PreparedWrite;
import com.hoangluongtran0309.dbbackup.core.exception.BackupFailedException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.LogicalBackupPort;
import com.hoangluongtran0309.dbbackup.core.model.ArtifactReference;


/**
 * Starts backups and runs them.
 *
 * <p>The two halves are deliberately separate. {@link #start} validates,
 * persists a RUNNING row and returns — so an accepted backup has a stable URL
 * before any work begins, and a crash leaves an inspectable row rather than a
 * lost in-memory task. {@link #run} is what the background thread executes.
 */
@Service
public class RunBackupService {

    private static final Logger log = LoggerFactory.getLogger(RunBackupService.class);

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneOffset.UTC);

    private final DatabaseTargetRepository targets;
    private final BackupExecutionRepository executions;
    private final EngineAdapterRegistry adapters;
    private final ArtifactStorageService storage;
    private final EncryptionPort encryption;
    private final Executor jobExecutor;
    private final Clock clock;
    private final ApplyBackupRetentionService retention;
    private final NotificationDispatcher notifications;

    @Autowired
    public RunBackupService(DatabaseTargetRepository targets, BackupExecutionRepository executions,
            EngineAdapterRegistry adapters, ArtifactStorageService storage, EncryptionPort encryption,
            Executor jobExecutor, Clock clock, ApplyBackupRetentionService retention,
            NotificationDispatcher notifications) {
        this.targets = targets; this.executions = executions; this.adapters = adapters; this.storage = storage;
        this.encryption = encryption; this.jobExecutor = jobExecutor; this.clock = clock; this.retention = retention;
        this.notifications = notifications;
    }

    public RunBackupService(DatabaseTargetRepository targets, BackupExecutionRepository executions,
            EngineAdapterRegistry adapters, ArtifactStorageService storage, EncryptionPort encryption,
            Executor jobExecutor, Clock clock, ApplyBackupRetentionService retention) {
        this(targets, executions, adapters, storage, encryption, jobExecutor, clock, retention, null);
    }

    public RunBackupService(DatabaseTargetRepository targets, BackupExecutionRepository executions,
            EngineAdapterRegistry adapters, com.hoangluongtran0309.dbbackup.core.port.StoragePort storage,
            EncryptionPort encryption, Executor jobExecutor, Clock clock, ApplyBackupRetentionService retention) {
        this(targets, executions, adapters, ArtifactStorageService.localOnly(storage), encryption,
                jobExecutor, clock, retention, null);
    }

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
                BackupExecution.started(UUID.randomUUID(), targetId, target.getStorageProfileId(), clock.instant()));

        try {
            jobExecutor.execute(() -> run(execution.getId(), target, backupEngine));
        } catch (RejectedExecutionException e) {
            // The queue is full. Saying so beats a row that sits at RUNNING
            // forever because nothing ever picked it up.
            notify(target, finish(execution, "Too many jobs are already running or queued. Try again shortly."),
                    NotificationEventType.BACKUP_FAILED);
        }
        return execution.getId();
    }

    /** Runs one accepted backup. Called on a background thread; never throws. */
    void run(UUID executionId, DatabaseTarget target, LogicalBackupPort backupEngine) {
        BackupExecution execution = executions.findById(executionId).orElseThrow();
        notify(target, execution, NotificationEventType.BACKUP_STARTED);
        String filename = artifactFileName(target, execution.getStartedAt(), backupEngine.artifactSuffix());
        ArtifactReference published = null;
        try (PreparedWrite destination = storage.prepareWrite(
                execution.getStorageProfileId(), target.getId(), executionId, filename)) {
            // Decrypted here, at the last moment and on the thread that uses
            // it, rather than being carried through the queue.
            DatabaseConnection connection = connectionTo(target);

            long reportedSize = backupEngine.dumpTo(connection, destination.path(), executionId);
            // Read back from disk once the engine has closed the file, so the
            // checksum describes what was stored rather than what was sent.
            long sizeBytes = storage.size(destination.path(), destination.remote(), reportedSize);
            String sha256 = storage.sha256(destination.path(), destination.remote());
            published = storage.publish(destination);
            BackupExecution succeeded = executions.save(
                    execution.succeeded(published.locator(), sizeBytes, sha256, clock.instant()));
            notify(target, succeeded, NotificationEventType.BACKUP_SUCCESS);
            log.info("Backup {} of target {} succeeded: {} ({} bytes, sha256 {})",
                    executionId, target.getName(), published.locator(), sizeBytes, sha256);

        } catch (BackupFailedException e) {
            if (execution.getStorageProfileId() != null) {
                cleanupFailedArtifact(execution, published, filename);
            }
            notify(target, finish(execution, e.getMessage()), NotificationEventType.BACKUP_FAILED);
            return;
        } catch (RuntimeException e) {
            // Anything unexpected still has to land on the row, or the console
            // shows RUNNING for a job that has already died.
            log.error("Backup {} of target {} failed unexpectedly", executionId, target.getName(), e);
            cleanupFailedArtifact(execution, published, filename);
            notify(target, finish(execution, e.toString()), NotificationEventType.BACKUP_FAILED);
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
                BackupExecution failed = finish(execution, message);
                notifyIfTargetExists(failed, NotificationEventType.BACKUP_FAILED);
                return;
            }
            try {
                adapter.abortInterrupted(execution.getId(), () -> connectionTo(target));
            } catch (RuntimeException e) {
                message += " External engine cleanup was not confirmed: " + e.getMessage();
                log.error("Could not stop external work for interrupted backup {}", execution.getId(), e);
            }
            try {
                String filename = artifactFileName(target, execution.getStartedAt(), adapter.artifactSuffix());
                try (PreparedWrite partial = storage.prepareWrite(
                        execution.getStorageProfileId(), target.getId(), execution.getId(), filename)) {
                    storage.delete(partial.reference());
                }
            } catch (RuntimeException e) {
                message += " Partial artifact cleanup failed: " + e.getMessage();
                log.error("Could not remove partial artifact for interrupted backup {}", execution.getId(), e);
            }
            BackupExecution failed = finish(execution, message);
            notifyIfTargetExists(failed, NotificationEventType.BACKUP_FAILED);
        });
        if (!stranded.isEmpty()) {
            log.warn("Marked {} backup(s) left running by a previous process as failed", stranded.size());
        }
        return stranded.size();
    }

    private BackupExecution finish(BackupExecution execution, String message) {
        return executions.save(execution.failed(message, clock.instant()));
    }

    private void notifyIfTargetExists(BackupExecution execution, NotificationEventType event) {
        targets.findById(execution.getTargetId()).ifPresent(target -> notify(target, execution, event));
    }

    private void notify(DatabaseTarget target, BackupExecution execution, NotificationEventType event) {
        if (notifications != null) notifications.publishBackup(target, execution, event);
    }

    private void cleanupFailedArtifact(BackupExecution execution, ArtifactReference published, String filename) {
        try {
            if (published != null) {
                storage.delete(published);
            } else {
                try (PreparedWrite planned = storage.prepareWrite(
                        execution.getStorageProfileId(), execution.getTargetId(), execution.getId(), filename)) {
                    storage.delete(planned.reference());
                }
            }
        } catch (RuntimeException cleanupFailure) {
            log.warn("Could not clean up artifact for failed backup {}", execution.getId(), cleanupFailure);
        }
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
