package com.hoangluongtran0309.dbbackup.core.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;

public interface BackupRetentionPolicyRepository {

    BackupRetentionPolicy save(BackupRetentionPolicy policy);

    Optional<BackupRetentionPolicy> findByTargetId(UUID targetId);

    List<BackupRetentionPolicy> findAll();

    void deleteByTargetId(UUID targetId);

    /**
     * Records a sweep only if the policy has not been edited or disabled since
     * it began. This method never inserts a missing policy.
     */
    boolean recordRunResult(
            UUID targetId,
            Instant expectedUpdatedAt,
            Instant ranAt,
            int deletedCount,
            String error);
}
