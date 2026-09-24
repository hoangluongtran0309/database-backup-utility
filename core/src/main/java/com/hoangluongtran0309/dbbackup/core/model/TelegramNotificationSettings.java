package com.hoangluongtran0309.dbbackup.core.model;

public record TelegramNotificationSettings(String botToken, String chatId)
        implements NotificationChannelSettings {
    @Override public NotificationChannelType type() { return NotificationChannelType.TELEGRAM; }
}
