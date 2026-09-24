package com.hoangluongtran0309.dbbackup.core.model;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** Framework-free payload shared by all notification adapters. */
public record NotificationMessage(
        NotificationEventType event, Instant occurredAt,
        UUID sourceTargetId, String sourceTargetName, DatabaseEngine sourceTargetEngine,
        UUID destinationTargetId, String destinationTargetName, DatabaseEngine destinationTargetEngine,
        UUID backupExecutionId, UUID restoreExecutionId, String status, String errorMessage,
        UUID channelId, String channelName) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC);

    public static NotificationMessage test(UUID channelId, String channelName, Instant occurredAt) {
        return new NotificationMessage(NotificationEventType.TEST, occurredAt,
                null, null, null, null, null, null, null, null, null, null, channelId, channelName);
    }
    public boolean isTest() { return event == NotificationEventType.TEST; }
    public String title() {
        return isTest() ? "[DB Backup] Test notification"
                : "[DB Backup] %s — %s".formatted(event,
                        destinationTargetName != null ? destinationTargetName : sourceTargetName);
    }
    public String text() {
        StringBuilder value = new StringBuilder(title()).append("\nTime: ").append(TIME.format(occurredAt));
        if (isTest()) return value.append("\nChannel: ").append(channelName)
                .append("\nThis is a test message from DB Backup Utility.").toString();
        if (sourceTargetName != null) value.append("\nSource: ").append(sourceTargetName)
                .append(" (").append(sourceTargetEngine).append(')');
        if (destinationTargetName != null) value.append("\nDestination: ").append(destinationTargetName)
                .append(" (").append(destinationTargetEngine).append(')');
        if (status != null) value.append("\nStatus: ").append(status);
        if (backupExecutionId != null) value.append("\nBackup execution: ").append(backupExecutionId);
        if (restoreExecutionId != null) value.append("\nRestore execution: ").append(restoreExecutionId);
        if (errorMessage != null && !errorMessage.isBlank()) value.append("\nError: ").append(errorMessage);
        return value.toString();
    }
}
