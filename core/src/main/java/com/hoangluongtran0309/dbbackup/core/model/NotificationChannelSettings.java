package com.hoangluongtran0309.dbbackup.core.model;

/** Ready-to-send settings. Secret values in these records must never be persisted or logged. */
public sealed interface NotificationChannelSettings
        permits TelegramNotificationSettings, SlackNotificationSettings,
                EmailNotificationSettings, WebhookNotificationSettings {
    NotificationChannelType type();
}
