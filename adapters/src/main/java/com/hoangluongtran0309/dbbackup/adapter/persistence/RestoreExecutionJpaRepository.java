package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;

interface RestoreExecutionJpaRepository extends JpaRepository<RestoreExecutionEntity, UUID> {

    List<RestoreExecutionEntity> findByStatus(ExecutionStatus status);

    /** A Slice, not a Page: it reads one row past the page instead of counting the table. */
    Slice<RestoreExecutionEntity> findAllBy(Pageable pageable);

    long countByBackupExecutionId(UUID backupExecutionId);

    long countByBackupExecutionIdIn(Collection<UUID> backupExecutionIds);

    @Query("""
            SELECT COUNT(r) FROM RestoreExecutionEntity r
             WHERE r.targetId = :targetId
                OR r.backupExecutionId IN (SELECT b.id FROM BackupExecutionEntity b WHERE b.targetId = :targetId)
            """)
    long countInvolvingTarget(@Param("targetId") UUID targetId);

    void deleteByBackupExecutionId(UUID backupExecutionId);

    void deleteByTargetId(UUID targetId);
}
