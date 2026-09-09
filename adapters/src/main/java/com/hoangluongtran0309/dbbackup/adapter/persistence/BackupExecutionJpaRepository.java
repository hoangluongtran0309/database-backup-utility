package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.hoangluongtran0309.dbbackup.core.model.BackupStatus;

interface BackupExecutionJpaRepository extends JpaRepository<BackupExecutionEntity, UUID> {

    List<BackupExecutionEntity> findByStatus(BackupStatus status);
}
