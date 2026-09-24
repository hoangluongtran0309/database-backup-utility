package com.hoangluongtran0309.dbbackup.core.model;

public record EmailNotificationSettings(String recipient) implements NotificationChannelSettings {
    @Override public NotificationChannelType type() { return NotificationChannelType.EMAIL; }
}
