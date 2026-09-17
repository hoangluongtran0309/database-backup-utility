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

        BackupExecution execution = executions.save(
                BackupExecution.started(UUID.randomUUID(), targetId, clock.instant()));

        try {
            jobExecutor.execute(() -> run(execution.getId(), target));
        } catch (RejectedExecutionException e) {
            // The queue is full. Saying so beats a row that sits at RUNNING
            // forever because nothing ever picked it up.
            finish(execution, "Too many jobs are already running or queued. Try again shortly.");
        }
        return execution.getId();
    }

    /** Runs one accepted backup. Called on a background thread; never throws. */
    void run(UUID executionId, DatabaseTarget target) {
        BackupExecution execution = executions.findById(executionId).orElseThrow();
        LogicalBackupPort backupEngine = adapters.backupFor(target.getEngine());
        Path destination = storage.locationFor(
                artifactFileName(target, execution.getStartedAt(), backupEngine.artifactSuffix()));

        try {
            // Decrypted here, at the last moment and on the thread that uses
            // it, rather than being carried through the queue.
            DatabaseConnection connection =
                    DatabaseConnection.to(target, encryption.decrypt(target.getPasswordCiphertext()));

            long sizeBytes = backupEngine.dumpTo(connection, destination);
            // Read back from disk once the engine has closed the file, so the
            // checksum describes what was stored rather than what was sent.
            String sha256 = storage.sha256Of(destination);
            executions.save(execution.succeeded(destination.toString(), sizeBytes, sha256, clock.instant()));
            log.info("Backup {} of target {} succeeded: {} ({} bytes, sha256 {})",
                    executionId, target.getName(), destination, sizeBytes, sha256);

        } catch (BackupFailedException e) {
            finish(execution, e.getMessage());
        } catch (RuntimeException e) {
            // Anything unexpected still has to land on the row, or the console
            // shows RUNNING for a job that has already died.
            log.error("Backup {} of target {} failed unexpectedly", executionId, target.getName(), e);
            storage.delete(destination);
            finish(execution, e.toString());
        }
    }

    /**
     * Marks every execution left RUNNING by a previous process as failed.
     *
     * <p>Backups run in this process and nowhere else, so a RUNNING row at
     * startup cannot still be running. Without this the console would show it
     * as in progress forever.
     */
    public int failInterruptedBackups() {
        List<BackupExecution> stranded = executions.findRunning();
        stranded.forEach(execution -> finish(
                execution, "Interrupted: the application stopped while this backup was running."));
        if (!stranded.isEmpty()) {
            log.warn("Marked {} backup(s) left running by a previous process as failed", stranded.size());
        }
        return stranded.size();
    }

    private void finish(BackupExecution execution, String message) {
        executions.save(execution.failed(message, clock.instant()));
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
        String schema = target.getDatabaseName().replaceAll("[^a-zA-Z0-9._-]", "_");
        return "%s_%s%s".formatted(schema, TIMESTAMP.format(startedAt), suffix);
    }
}
