package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;

public interface RestoreVerificationExecutionRepository {

    RestoreVerificationExecution save(RestoreVerificationExecution execution);

    Optional<RestoreVerificationExecution> findById(UUID id);

    List<RestoreVerificationExecution> findForBackupNewestFirst(UUID backupExecutionId);

    Optional<RestoreVerificationExecution> findLatestForBackup(UUID backupExecutionId);

    List<RestoreVerificationExecution> findRunning();

    boolean existsRunningForBackup(UUID backupExecutionId);
}
