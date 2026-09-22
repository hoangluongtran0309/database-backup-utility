package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;

final class BackupRetentionPolicyMapper {

    private BackupRetentionPolicyMapper() {
    }

    static BackupRetentionPolicy toDomain(BackupRetentionPolicyEntity entity) {
        return BackupRetentionPolicy.builder()
                .targetId(entity.getTargetId())
                .keepSuccessful(entity.getKeepSuccessful())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .lastRunAt(entity.getLastRunAt())
                .lastDeletedCount(entity.getLastDeletedCount())
                .lastError(entity.getLastError())
                .build();
    }

    static BackupRetentionPolicyEntity toEntity(BackupRetentionPolicy policy) {
        BackupRetentionPolicyEntity entity = new BackupRetentionPolicyEntity();
        entity.setTargetId(policy.getTargetId());
        entity.setKeepSuccessful(policy.getKeepSuccessful());
        entity.setCreatedAt(policy.getCreatedAt());
        entity.setUpdatedAt(policy.getUpdatedAt());
        entity.setLastRunAt(policy.getLastRunAt());
        entity.setLastDeletedCount(policy.getLastDeletedCount());
        entity.setLastError(policy.getLastError());
        return entity;
    }
}
