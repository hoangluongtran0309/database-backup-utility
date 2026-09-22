package com.hoangluongtran0309.dbbackup.application.retention;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.application.backup.BackupActivityGuard;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService.RetentionDeletion;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.BackupRetentionPolicyRepository;

import lombok.RequiredArgsConstructor;

/** Applies one target's policy after a new successful backup is durable. */
@Service
@RequiredArgsConstructor
public class ApplyBackupRetentionService {

    private static final Logger log = LoggerFactory.getLogger(ApplyBackupRetentionService.class);

    public record RunResult(int deleted, int protectedSinceSelection, String error) {
        public boolean successful() {
            return error == null;
        }
    }

    private final BackupRetentionPolicyRepository policies;
    private final BackupExecutionRepository backups;
    private final BackupArtifactService artifacts;
    private final BackupActivityGuard activityGuard;
    private final Clock clock;

    /** Empty means retention is disabled for the target. */
    public Optional<RunResult> applyAfterSuccessfulBackup(UUID targetId) {
        Optional<BackupRetentionPolicy> configured = policies.findByTargetId(targetId);
        if (configured.isEmpty()) {
            return Optional.empty();
        }

        BackupRetentionPolicy policy = configured.get();
        int deleted = 0;
        int protectedSinceSelection = 0;
        String error = null;
        try {
            List<BackupExecution> candidates =
                    backups.findRetentionCandidates(targetId, policy.getKeepSuccessful());
            for (BackupExecution candidate : candidates) {
                RetentionDeletion outcome = activityGuard.withBackup(
                        candidate.getId(), () -> artifacts.deleteForRetention(candidate.getId()));
                if (outcome == RetentionDeletion.DELETED) {
                    deleted++;
                } else if (outcome == RetentionDeletion.PROTECTED) {
                    protectedSinceSelection++;
                }
            }
        } catch (RuntimeException e) {
            error = messageOf(e);
            log.error("Automatic retention for target {} stopped after deleting {} backup(s)",
                    targetId, deleted, e);
        }

        BackupRetentionPolicy completed = policy.completed(deleted, error, clock.instant());
        boolean recorded = policies.recordRunResult(
                targetId,
                policy.getUpdatedAt(),
                completed.getLastRunAt(),
                completed.getLastDeletedCount(),
                completed.getLastError());
        if (recorded) {
            if (error == null) {
                log.info("Automatic retention for target {} deleted {} backup(s)", targetId, deleted);
            }
        } else {
            log.info("Discarded retention result for target {} because its policy changed", targetId);
        }
        return Optional.of(new RunResult(deleted, protectedSinceSelection, error));
    }

    private static String messageOf(RuntimeException e) {
        return e.getMessage() == null || e.getMessage().isBlank()
                ? e.getClass().getSimpleName()
                : e.getMessage();
    }
}
