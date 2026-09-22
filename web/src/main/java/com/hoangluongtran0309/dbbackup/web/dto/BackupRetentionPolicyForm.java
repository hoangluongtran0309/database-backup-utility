package com.hoangluongtran0309.dbbackup.web.dto;

import com.hoangluongtran0309.dbbackup.application.retention.SaveBackupRetentionPolicyCommand;
import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class BackupRetentionPolicyForm {

    @NotNull(message = "Enter how many successful backups to keep")
    @Min(value = 1, message = "Keep successful backups must be at least 1")
    private Integer keepSuccessful;

    public static BackupRetentionPolicyForm blank() {
        return new BackupRetentionPolicyForm();
    }

    public static BackupRetentionPolicyForm of(BackupRetentionPolicy policy) {
        BackupRetentionPolicyForm form = new BackupRetentionPolicyForm();
        form.keepSuccessful = policy.getKeepSuccessful();
        return form;
    }

    public SaveBackupRetentionPolicyCommand toCommand() {
        return new SaveBackupRetentionPolicyCommand(keepSuccessful);
    }
}
