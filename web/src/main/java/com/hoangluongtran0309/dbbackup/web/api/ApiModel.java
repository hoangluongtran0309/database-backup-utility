package com.hoangluongtran0309.dbbackup.web.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.hoangluongtran0309.dbbackup.application.schedule.ManageBackupScheduleService.ScheduleView;
import com.hoangluongtran0309.dbbackup.application.retention.ManageBackupRetentionService.RetentionView;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;

final class ApiModel {
    private ApiModel() { }

    static Map<String, Object> target(DatabaseTarget value) {
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("name", value.getName());
        result.put("engine", value.getEngine()); result.put("host", value.getHost());
        result.put("port", value.getPort()); result.put("database", value.getDatabaseName());
        result.put("username", value.getUsername()); result.put("authenticationDatabase", value.getAuthenticationDatabase());
        result.put("dataPumpDirectory", value.getDataPumpDirectory()); result.put("storageProfileId", value.getStorageProfileId());
        result.put("verifyAfterBackup", value.isVerifyAfterBackup()); result.put("createdAt", value.getCreatedAt());
        result.put("lastConnectionCheck", value.getLastConnectionCheck());
        return result;
    }

    static Map<String, Object> storage(StorageProfile value) {
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("name", value.getName()); result.put("provider", value.getProvider());
        result.put("endpoint", value.getEndpoint()); result.put("region", value.getRegion());
        result.put("projectId", value.getProjectId()); result.put("accountName", value.getAccountName());
        result.put("bucket", value.getBucket()); result.put("keyPrefix", value.getKeyPrefix());
        result.put("pathStyle", value.isPathStyle()); result.put("credentialMode", value.getCredentialMode());
        result.put("accessKeyId", value.getAccessKeyId());
        result.put("secretConfigured", value.getSecretAccessKeyCiphertext() != null
                || value.getServiceAccountJsonCiphertext() != null || value.getAccountKeyCiphertext() != null);
        result.put("createdAt", value.getCreatedAt()); result.put("updatedAt", value.getUpdatedAt());
        result.put("lastConnectionCheck", value.getLastConnectionCheck());
        return result;
    }

    static Map<String, Object> channel(NotificationChannel value) {
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("name", value.getName()); result.put("type", value.getType());
        result.put("chatId", value.getChatId()); result.put("emailTo", value.getEmailTo());
        result.put("secretConfigured", value.getBotTokenCiphertext() != null || value.getWebhookUrlCiphertext() != null);
        result.put("createdAt", value.getCreatedAt()); result.put("updatedAt", value.getUpdatedAt());
        result.put("lastConnectionCheck", value.getLastConnectionCheck());
        return result;
    }

    static Map<String, Object> schedule(ScheduleView view) {
        var value = view.schedule();
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("name", value.getName()); result.put("targetId", value.getTargetId());
        result.put("cronExpression", value.getCronExpression()); result.put("zoneId", value.getZoneId());
        result.put("enabled", value.isEnabled()); result.put("createdAt", value.getCreatedAt());
        result.put("updatedAt", value.getUpdatedAt()); result.put("nextFireTime", view.nextFireTime().orElse(null));
        return result;
    }

    static Map<String, Object> retention(RetentionView view) {
        Map<String, Object> result = map();
        result.put("target", target(view.target())); result.put("policy", view.policy().orElse(null));
        return result;
    }

    static Map<String, Object> backup(BackupExecution value) {
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("targetId", value.getTargetId()); result.put("status", value.getStatus());
        result.put("startedAt", value.getStartedAt()); result.put("finishedAt", value.getFinishedAt());
        result.put("artifactLocator", value.getArtifactLocator()); result.put("storageProfileId", value.getStorageProfileId());
        result.put("sizeBytes", value.getSizeBytes()); result.put("sha256", value.getSha256());
        result.put("errorMessage", value.getErrorMessage());
        return result;
    }

    static Map<String, Object> restore(RestoreExecution value) {
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("backupExecutionId", value.getBackupExecutionId());
        result.put("targetId", value.getTargetId()); result.put("status", value.getStatus());
        result.put("startedAt", value.getStartedAt()); result.put("finishedAt", value.getFinishedAt());
        result.put("errorMessage", value.getErrorMessage());
        return result;
    }

    static Map<String, Object> verification(RestoreVerificationExecution value) {
        Map<String, Object> result = map();
        result.put("id", value.getId()); result.put("backupExecutionId", value.getBackupExecutionId());
        result.put("status", value.getStatus()); result.put("startedAt", value.getStartedAt());
        result.put("finishedAt", value.getFinishedAt()); result.put("checkedObjects", value.getCheckedObjects());
        result.put("resultSummary", value.getResultSummary()); result.put("errorMessage", value.getErrorMessage());
        return result;
    }

    static Map<String, Object> subscription(TargetNotificationSubscription value) {
        return Map.of("channelId", value.channelId(), "events", value.events());
    }

    private static Map<String, Object> map() { return new LinkedHashMap<>(); }
}
