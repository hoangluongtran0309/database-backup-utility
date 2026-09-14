package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;

interface BackupExecutionJpaRepository extends JpaRepository<BackupExecutionEntity, UUID> {

    List<BackupExecutionEntity> findByStatus(ExecutionStatus status);

    boolean existsByTargetId(UUID targetId);

    /** A Slice, not a Page: it reads one row past the page instead of counting the table. */
    Slice<BackupExecutionEntity> findAllBy(Pageable pageable);

    /**
     * PostgreSQL's DISTINCT ON: the first row of each target in this order,
     * exact even when two backups started in the same instant. The metadata
     * store is PostgreSQL and nothing else, so the dialect is not a cost.
     */
    @Query(value = """
            SELECT DISTINCT ON (target_id) * FROM backup_executions
             ORDER BY target_id, started_at DESC, id DESC
            """, nativeQuery = true)
    List<BackupExecutionEntity> findLatestPerTarget();

    @Query(value = """
            SELECT DISTINCT ON (target_id) * FROM backup_executions
             WHERE status = 'SUCCEEDED'
             ORDER BY target_id, started_at DESC, id DESC
            """, nativeQuery = true)
    List<BackupExecutionEntity> findLatestSucceededPerTarget();
}
