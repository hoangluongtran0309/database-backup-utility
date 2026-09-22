package com.hoangluongtran0309.dbbackup.application.retention;

/** Operator input for enabling or changing one target's retention rule. */
public record SaveBackupRetentionPolicyCommand(int keepSuccessful) {
}
