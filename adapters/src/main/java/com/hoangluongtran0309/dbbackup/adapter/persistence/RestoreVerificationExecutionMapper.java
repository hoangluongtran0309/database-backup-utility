package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;

final class RestoreVerificationExecutionMapper {
    private RestoreVerificationExecutionMapper() { }

    static RestoreVerificationExecutionEntity toEntity(RestoreVerificationExecution value) {
        RestoreVerificationExecutionEntity entity = new RestoreVerificationExecutionEntity();
        entity.setId(value.getId());
        entity.setBackupExecutionId(value.getBackupExecutionId());
        entity.setStatus(value.getStatus());
        entity.setStartedAt(value.getStartedAt());
        entity.setFinishedAt(value.getFinishedAt());
        entity.setCheckedObjects(value.getCheckedObjects());
        entity.setResultSummary(value.getResultSummary());
        entity.setErrorMessage(value.getErrorMessage());
        return entity;
    }

    static RestoreVerificationExecution toDomain(RestoreVerificationExecutionEntity entity) {
        return RestoreVerificationExecution.builder()
                .id(entity.getId())
                .backupExecutionId(entity.getBackupExecutionId())
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .checkedObjects(entity.getCheckedObjects())
                .resultSummary(entity.getResultSummary())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
