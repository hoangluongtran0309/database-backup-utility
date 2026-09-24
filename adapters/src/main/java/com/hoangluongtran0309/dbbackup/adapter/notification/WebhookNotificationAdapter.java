package com.hoangluongtran0309.dbbackup.adapter.notification;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.model.WebhookNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.port.NotificationPort;

@Component
public class WebhookNotificationAdapter implements NotificationPort {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final HttpNotificationSupport http;
    public WebhookNotificationAdapter() { this(new HttpNotificationSupport()); }
    WebhookNotificationAdapter(HttpNotificationSupport http) { this.http = http; }
    @Override public NotificationChannelType supportedType() { return NotificationChannelType.WEBHOOK; }

    @Override public void send(NotificationChannelSettings settings, NotificationMessage message) {
        if (!(settings instanceof WebhookNotificationSettings webhook)) {
            throw new IllegalArgumentException("Webhook settings required");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", message.event());
        body.put("isTest", message.isTest());
        body.put("occurredAt", message.occurredAt());
        body.put("channel", nested("id", message.channelId(), "name", message.channelName()));
        body.put("sourceTarget", nested("id", message.sourceTargetId(), "name", message.sourceTargetName(),
                "engine", message.sourceTargetEngine()));
        body.put("destinationTarget", nested("id", message.destinationTargetId(),
                "name", message.destinationTargetName(), "engine", message.destinationTargetEngine()));
        body.put("backupExecutionId", message.backupExecutionId());
        body.put("restoreExecutionId", message.restoreExecutionId());
        body.put("status", message.status());
        body.put("errorMessage", message.errorMessage());
        try {
            http.postJson(webhook.url(), objectMapper.writeValueAsString(body), "Webhook");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Webhook notification payload failed", e);
        }
    }

    private static Map<String, Object> nested(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }
}
