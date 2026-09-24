package com.hoangluongtran0309.dbbackup.application.notification;

import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.EmailNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelSettings;
import com.hoangluongtran0309.dbbackup.core.model.SlackNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.TelegramNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.WebhookNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class NotificationCredentials {
    private final EncryptionPort encryption;

    public NotificationChannelSettings decrypt(NotificationChannel channel) {
        return switch (channel.getType()) {
            case TELEGRAM -> new TelegramNotificationSettings(
                    encryption.decrypt(channel.getBotTokenCiphertext()), channel.getChatId());
            case SLACK -> new SlackNotificationSettings(encryption.decrypt(channel.getWebhookUrlCiphertext()));
            case EMAIL -> new EmailNotificationSettings(channel.getEmailTo());
            case WEBHOOK -> new WebhookNotificationSettings(encryption.decrypt(channel.getWebhookUrlCiphertext()));
        };
    }

    String encrypt(String plainText) { return encryption.encrypt(plainText); }
}
