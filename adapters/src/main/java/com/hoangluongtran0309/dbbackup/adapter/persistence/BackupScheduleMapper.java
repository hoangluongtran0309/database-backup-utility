package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;

final class BackupScheduleMapper {

    private BackupScheduleMapper() {
    }

    static BackupScheduleEntity toEntity(BackupSchedule schedule) {
        BackupScheduleEntity entity = new BackupScheduleEntity();
        entity.setId(schedule.getId());
        entity.setTargetId(schedule.getTargetId());
        entity.setName(schedule.getName());
        entity.setCronExpression(schedule.getCronExpression());
        entity.setZoneId(schedule.getZoneId());
        entity.setEnabled(schedule.isEnabled());
        entity.setCreatedAt(schedule.getCreatedAt());
        entity.setUpdatedAt(schedule.getUpdatedAt());
        return entity;
    }

    static BackupSchedule toDomain(BackupScheduleEntity entity) {
        return BackupSchedule.builder()
                .id(entity.getId())
                .targetId(entity.getTargetId())
                .name(entity.getName())
                .cronExpression(entity.getCronExpression())
                .zoneId(entity.getZoneId())
                .enabled(entity.isEnabled())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
