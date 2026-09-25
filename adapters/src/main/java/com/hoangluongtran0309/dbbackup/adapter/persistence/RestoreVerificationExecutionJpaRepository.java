package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationStatus;

interface RestoreVerificationExecutionJpaRepository
        extends JpaRepository<RestoreVerificationExecutionEntity, UUID> {
    List<RestoreVerificationExecutionEntity> findByBackupExecutionIdOrderByStartedAtDescIdDesc(UUID backupId);
    Optional<RestoreVerificationExecutionEntity> findFirstByBackupExecutionIdOrderByStartedAtDescIdDesc(UUID backupId);
    List<RestoreVerificationExecutionEntity> findByStatus(RestoreVerificationStatus status);
    boolean existsByBackupExecutionIdAndStatus(UUID backupId, RestoreVerificationStatus status);
}
