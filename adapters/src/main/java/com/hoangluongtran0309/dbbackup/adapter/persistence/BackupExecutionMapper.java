package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;

final class BackupExecutionMapper {

    private BackupExecutionMapper() {
    }

    static BackupExecutionEntity toEntity(BackupExecution execution) {
        BackupExecutionEntity entity = new BackupExecutionEntity();
        entity.setId(execution.getId());
        entity.setTargetId(execution.getTargetId());
        entity.setStatus(execution.getStatus());
        entity.setStartedAt(execution.getStartedAt());
        entity.setFinishedAt(execution.getFinishedAt());
        entity.setArtifactLocator(execution.getArtifactLocator());
        entity.setStorageProfileId(execution.getStorageProfileId());
        entity.setSizeBytes(execution.getSizeBytes());
        entity.setSha256(execution.getSha256());
        entity.setErrorMessage(execution.getErrorMessage());
        return entity;
    }

    static BackupExecution toDomain(BackupExecutionEntity entity) {
        return BackupExecution.builder()
                .id(entity.getId())
                .targetId(entity.getTargetId())
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .artifactLocator(entity.getArtifactLocator())
                .storageProfileId(entity.getStorageProfileId())
                .sizeBytes(entity.getSizeBytes())
                .sha256(entity.getSha256())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
