package com.hoangluongtran0309.dbbackup.core.model;

public record WebhookNotificationSettings(String url) implements NotificationChannelSettings {
    @Override public NotificationChannelType type() { return NotificationChannelType.WEBHOOK; }
}
