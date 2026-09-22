package com.hoangluongtran0309.dbbackup.web.dto;

import java.util.UUID;

import com.hoangluongtran0309.dbbackup.application.schedule.SaveBackupScheduleCommand;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BackupScheduleForm {

    @NotBlank(message = "Name is required")
    @Size(max = BackupSchedule.MAX_NAME_LENGTH, message = "Name must be at most 100 characters")
    private String name;

    @NotNull(message = "Choose a target")
    private UUID targetId;

    @NotBlank(message = "Cron expression is required")
    @Size(max = BackupSchedule.MAX_CRON_LENGTH, message = "Cron expression must be at most 120 characters")
    private String cronExpression;

    @NotBlank(message = "Time zone is required")
    @Size(max = BackupSchedule.MAX_ZONE_LENGTH, message = "Time zone must be at most 64 characters")
    private String zoneId;

    private boolean enabled = true;

    public static BackupScheduleForm blank(UUID targetId) {
        BackupScheduleForm form = new BackupScheduleForm();
        form.targetId = targetId;
        form.cronExpression = "0 0 2 * * ?";
        form.zoneId = "UTC";
        form.enabled = true;
        return form;
    }

    public static BackupScheduleForm of(BackupSchedule schedule) {
        BackupScheduleForm form = new BackupScheduleForm();
        form.name = schedule.getName();
        form.targetId = schedule.getTargetId();
        form.cronExpression = schedule.getCronExpression();
        form.zoneId = schedule.getZoneId();
        form.enabled = schedule.isEnabled();
        return form;
    }

    public SaveBackupScheduleCommand toCommand() {
        return new SaveBackupScheduleCommand(name, targetId, cronExpression, zoneId, enabled);
    }
}
