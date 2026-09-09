package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;

interface RestoreExecutionJpaRepository extends JpaRepository<RestoreExecutionEntity, UUID> {

    List<RestoreExecutionEntity> findByStatus(ExecutionStatus status);

    long countByBackupExecutionId(UUID backupExecutionId);

    void deleteByBackupExecutionId(UUID backupExecutionId);
}
