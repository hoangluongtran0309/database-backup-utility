package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BackupRetentionPolicyJpaRepository extends JpaRepository<BackupRetentionPolicyEntity, UUID> {

    @Modifying
    @Query("""
            UPDATE BackupRetentionPolicyEntity p
               SET p.lastRunAt = :ranAt,
                   p.lastDeletedCount = :deletedCount,
                   p.lastError = :error
             WHERE p.targetId = :targetId
               AND p.updatedAt = :expectedUpdatedAt
            """)
    int recordRunResult(
            @Param("targetId") UUID targetId,
            @Param("expectedUpdatedAt") Instant expectedUpdatedAt,
            @Param("ranAt") Instant ranAt,
            @Param("deletedCount") int deletedCount,
            @Param("error") String error);
}
