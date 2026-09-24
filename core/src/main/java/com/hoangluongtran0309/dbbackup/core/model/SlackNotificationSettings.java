package com.hoangluongtran0309.dbbackup.core.model;

public record SlackNotificationSettings(String webhookUrl) implements NotificationChannelSettings {
    @Override public NotificationChannelType type() { return NotificationChannelType.SLACK; }
}
