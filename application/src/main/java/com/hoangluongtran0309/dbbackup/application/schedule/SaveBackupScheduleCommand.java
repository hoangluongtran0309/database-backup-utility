package com.hoangluongtran0309.dbbackup.application.schedule;

import java.util.UUID;

/** Values accepted when a recurring backup is created or edited. */
public record SaveBackupScheduleCommand(
        String name,
        UUID targetId,
        String cronExpression,
        String zoneId,
        boolean enabled) {
}
