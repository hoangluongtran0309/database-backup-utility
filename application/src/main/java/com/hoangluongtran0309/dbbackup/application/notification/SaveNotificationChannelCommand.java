package com.hoangluongtran0309.dbbackup.application.notification;

import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;

public record SaveNotificationChannelCommand(
        String name,
        NotificationChannelType type,
        String botToken,
        String chatId,
        String webhookUrl,
        String emailTo) {
    public boolean suppliesBotToken() { return botToken != null && !botToken.isBlank(); }
    public boolean suppliesWebhookUrl() { return webhookUrl != null && !webhookUrl.isBlank(); }
}
