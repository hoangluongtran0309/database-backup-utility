package com.hoangluongtran0309.dbbackup.adapter.notification;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.model.TelegramNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.port.NotificationPort;

@Component
public class TelegramNotificationAdapter implements NotificationPort {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String apiBaseUrl;
    private final HttpNotificationSupport http;
    public TelegramNotificationAdapter() { this("https://api.telegram.org", new HttpNotificationSupport()); }
    TelegramNotificationAdapter(String apiBaseUrl, HttpNotificationSupport http) {
        this.apiBaseUrl = apiBaseUrl;
        this.http = http;
    }

    @Override public NotificationChannelType supportedType() { return NotificationChannelType.TELEGRAM; }
    @Override public void send(NotificationChannelSettings settings, NotificationMessage message) {
        if (!(settings instanceof TelegramNotificationSettings telegram)) {
            throw new IllegalArgumentException("Telegram settings required");
        }
        try {
            String body = objectMapper.writeValueAsString(
                    java.util.Map.of("chat_id", telegram.chatId(), "text", message.text()));
            String response = http.postJson(
                    apiBaseUrl + "/bot" + telegram.botToken() + "/sendMessage", body, "Telegram");
            if (!objectMapper.readTree(response).path("ok").asBoolean(false)) {
                throw new IllegalStateException("Telegram notification was rejected");
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Telegram notification payload failed", e);
        }
    }
}
