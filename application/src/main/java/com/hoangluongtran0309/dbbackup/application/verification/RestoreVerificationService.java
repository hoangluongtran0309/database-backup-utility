package com.hoangluongtran0309.dbbackup.application.verification;

import java.nio.file.Path;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.application.backup.BackupActivityGuard;
import com.hoangluongtran0309.dbbackup.application.notification.NotificationDispatcher;
import com.hoangluongtran0309.dbbackup.application.storage.ArtifactStorageService;
import com.hoangluongtran0309.dbbackup.application.storage.ArtifactStorageService.PreparedRead;
import com.hoangluongtran0309.dbbackup.core.model.ArtifactReference;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationPort;

/** Accepts and runs isolated restore-verification attempts without changing the backup outcome. */
@Service
public class RestoreVerificationService {
    private static final Logger log = LoggerFactory.getLogger(RestoreVerificationService.class);

    private final BackupExecutionRepository backups;
    private final RestoreVerificationExecutionRepository executions;
    private final DatabaseTargetRepository targets;
    private final ArtifactStorageService storage;
    private final Executor jobExecutor;
    private final Clock clock;
    private final BackupActivityGuard activityGuard;
    private final NotificationDispatcher notifications;
    private final boolean enabled;
    private final Map<DatabaseEngine, RestoreVerificationPort> adapters = new EnumMap<>(DatabaseEngine.class);

    public RestoreVerificationService(
            BackupExecutionRepository backups,
            RestoreVerificationExecutionRepository executions,
            DatabaseTargetRepository targets,
            ArtifactStorageService storage,
            Executor jobExecutor,
            Clock clock,
            BackupActivityGuard activityGuard,
            NotificationDispatcher notifications,
            List<RestoreVerificationPort> ports,
            @Value("${dbbackup.verification.enabled:false}") boolean enabled) {
        this.backups = backups;
        this.executions = executions;
        this.targets = targets;
        this.storage = storage;
        this.jobExecutor = jobExecutor;
        this.clock = clock;
        this.activityGuard = activityGuard;
        this.notifications = notifications;
        this.enabled = enabled;
        for (RestoreVerificationPort port : ports) {
            if (adapters.put(port.engine(), port) != null) {
                throw new IllegalStateException("Two restore-verification adapters claim " + port.engine());
            }
        }
    }

    public UUID start(UUID backupExecutionId) {
        return activityGuard.withBackup(backupExecutionId, () -> {
            BackupContext context = requireContext(backupExecutionId, true);
            RestoreVerificationExecution execution = begin(backupExecutionId);
            try {
                jobExecutor.execute(() -> run(execution.getId()));
            } catch (RejectedExecutionException e) {
                failAndNotify(context.target(), execution,
                        "Too many jobs are already running or queued. Try again shortly.");
            }
            return execution.getId();
        });
    }

    /** Runs after a successful backup on the worker already holding a bounded-pool slot. */
    public Optional<UUID> verifyAfterBackup(DatabaseTarget target, BackupExecution backup) {
        if (!enabled || !target.isVerifyAfterBackup()) {
            return Optional.empty();
        }
        return activityGuard.withBackup(backup.getId(), () -> {
            RestoreVerificationExecution execution = begin(backup.getId());
            run(execution.getId());
            return Optional.of(execution.getId());
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean supports(DatabaseEngine engine) {
        return adapters.containsKey(engine);
    }

    public boolean isAvailable(DatabaseEngine engine) {
        RestoreVerificationPort adapter = adapters.get(engine);
        return enabled && adapter != null && adapter.isAvailable();
    }

    public List<RestoreVerificationExecution> history(UUID backupExecutionId) {
        return executions.findForBackupNewestFirst(backupExecutionId);
    }

    public Optional<RestoreVerificationExecution> latest(UUID backupExecutionId) {
        return executions.findLatestForBackup(backupExecutionId);
    }

    void run(UUID verificationId) {
        RestoreVerificationExecution execution = executions.findById(verificationId).orElseThrow();
        BackupContext context;
        try {
            context = requireContext(execution.getBackupExecutionId(), false);
        } catch (RuntimeException e) {
            failWithoutNotification(execution, e.getMessage());
            return;
        }
        RestoreVerificationPort adapter = adapters.get(context.target().getEngine());
        if (adapter == null || !adapter.isAvailable()) {
            failAndNotify(context.target(), execution,
                    "Restore verification is enabled but no usable adapter is available for "
                            + context.target().getEngine().displayName());
            return;
        }

        ArtifactReference reference = new ArtifactReference(
                context.backup().getStorageProfileId(), context.backup().getArtifactLocator());
        try {
            if (!storage.exists(reference)) {
                failAndNotify(context.target(), execution,
                        "The backup artifact is no longer available. Nothing was verified.");
                return;
            }
            String filename = Path.of(reference.locator()).getFileName().toString();
            RestoreVerificationResult result;
            try (PreparedRead artifact = storage.materialize(reference, verificationId, filename)) {
                requireIntact(context.backup(), artifact.path(), artifact.staged());
                result = adapter.verify(verificationId, context.target(), artifact.path());
            }
            // The adapter and PreparedRead have both confirmed cleanup before
            // this terminal success becomes durable.
            RestoreVerificationExecution succeeded = executions.save(execution.succeeded(result, clock.instant()));
            notifications.publishVerification(context.target(), succeeded, NotificationEventType.VERIFICATION_SUCCESS);
            log.info("Restore verification {} for backup {} succeeded", verificationId, context.backup().getId());
        } catch (RuntimeException e) {
            log.error("Restore verification {} for backup {} failed", verificationId, context.backup().getId(), e);
            failAndNotify(context.target(), execution, usefulMessage(e));
        }
    }

    public int failInterruptedVerifications() {
        List<RestoreVerificationExecution> running = executions.findRunning();
        for (RestoreVerificationExecution execution : running) {
            String message = "Interrupted: the application stopped while restore verification was running.";
            DatabaseTarget target = null;
            try {
                BackupExecution backup = backups.findById(execution.getBackupExecutionId()).orElseThrow();
                target = targets.findById(backup.getTargetId()).orElseThrow();
                RestoreVerificationPort adapter = adapters.get(target.getEngine());
                if (adapter != null) adapter.abortInterrupted(execution.getId());
            } catch (RuntimeException e) {
                message += " Temporary database cleanup was not confirmed: " + usefulMessage(e);
            }
            try {
                storage.cleanup(execution.getId());
            } catch (RuntimeException e) {
                message += " Staging cleanup failed: " + usefulMessage(e);
            }
            RestoreVerificationExecution failed = executions.save(execution.failed(message, clock.instant()));
            if (target != null) {
                notifications.publishVerification(target, failed, NotificationEventType.VERIFICATION_FAILED);
            }
        }
        return running.size();
    }

    private RestoreVerificationExecution begin(UUID backupId) {
        if (executions.existsRunningForBackup(backupId)) {
            throw new IllegalStateException("A restore verification is already running for this backup");
        }
        return executions.save(RestoreVerificationExecution.started(UUID.randomUUID(), backupId, clock.instant()));
    }

    private BackupContext requireContext(UUID backupId, boolean requireAvailable) {
        if (!enabled) {
            throw new IllegalStateException("Restore verification is disabled for this deployment");
        }
        BackupExecution backup = backups.findById(backupId)
                .orElseThrow(() -> new NoSuchElementException("No backup execution with id " + backupId));
        if (backup.getStatus() != ExecutionStatus.SUCCEEDED) {
            throw new IllegalStateException("Only a successful backup can be restore-verified");
        }
        DatabaseTarget target = targets.findById(backup.getTargetId())
                .orElseThrow(() -> new NoSuchElementException("The target this backup came from no longer exists"));
        RestoreVerificationPort adapter = adapters.get(target.getEngine());
        if (adapter == null) {
            throw new IllegalStateException("Restore verification is not supported for "
                    + target.getEngine().displayName());
        }
        if (requireAvailable && !adapter.isAvailable()) {
            throw new IllegalStateException("Restore verification is unavailable for "
                    + target.getEngine().displayName());
        }
        return new BackupContext(backup, target);
    }

    private void requireIntact(BackupExecution backup, Path artifact, boolean staged) {
        if (!backup.hasChecksum()) return;
        String actual = storage.sha256(artifact, staged);
        if (!backup.getSha256().equals(actual)) {
            throw new IllegalStateException("The backup artifact does not match its recorded checksum; "
                    + "the temporary database was not started");
        }
    }

    private void failAndNotify(DatabaseTarget target, RestoreVerificationExecution execution, String message) {
        RestoreVerificationExecution failed = executions.save(execution.failed(message, clock.instant()));
        notifications.publishVerification(target, failed, NotificationEventType.VERIFICATION_FAILED);
    }

    private void failWithoutNotification(RestoreVerificationExecution execution, String message) {
        executions.save(execution.failed(message == null ? "Verification context no longer exists" : message,
                clock.instant()));
    }

    private static String usefulMessage(RuntimeException error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.toString() : error.getMessage();
    }

    private record BackupContext(BackupExecution backup, DatabaseTarget target) { }
}
