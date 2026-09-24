package com.hoangluongtran0309.dbbackup.core.model;

public enum NotificationChannelType {
    TELEGRAM("Telegram"),
    SLACK("Slack"),
    EMAIL("Email"),
    WEBHOOK("Webhook");

    private final String displayName;

    NotificationChannelType(String displayName) { this.displayName = displayName; }
    public String getDisplayName() { return displayName; }
}
