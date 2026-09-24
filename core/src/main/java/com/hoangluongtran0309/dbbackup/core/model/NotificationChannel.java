package com.hoangluongtran0309.dbbackup.core.model;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

/** A named, reusable notification destination. Secrets are encrypted at rest. */
@Getter
public final class NotificationChannel {
    private final UUID id;
    private final String name;
    private final NotificationChannelType type;
    private final String botTokenCiphertext;
    private final String chatId;
    private final String webhookUrlCiphertext;
    private final String emailTo;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final ConnectionCheck lastConnectionCheck;

    @Builder(toBuilder = true)
    private NotificationChannel(UUID id, String name, NotificationChannelType type,
            String botTokenCiphertext, String chatId, String webhookUrlCiphertext, String emailTo,
            Instant createdAt, Instant updatedAt, ConnectionCheck lastConnectionCheck) {
        this.id = require(id, "Channel id is required");
        this.name = text(name, "Channel name", 100);
        this.type = require(type, "Channel type is required");
        this.createdAt = require(createdAt, "Creation timestamp is required");
        this.updatedAt = require(updatedAt, "Update timestamp is required");
        this.lastConnectionCheck = lastConnectionCheck;
        switch (type) {
            case TELEGRAM -> {
                this.botTokenCiphertext = text(botTokenCiphertext, "Telegram bot token", 8192);
                this.chatId = text(chatId, "Telegram chat id", 100);
                this.webhookUrlCiphertext = absent(webhookUrlCiphertext, "Telegram channel must not contain a webhook URL");
                this.emailTo = absent(emailTo, "Telegram channel must not contain an email recipient");
            }
            case SLACK, WEBHOOK -> {
                this.botTokenCiphertext = absent(botTokenCiphertext, "Webhook channel must not contain a Telegram token");
                this.chatId = absent(chatId, "Webhook channel must not contain a Telegram chat id");
                this.webhookUrlCiphertext = text(webhookUrlCiphertext, "Webhook URL", 8192);
                this.emailTo = absent(emailTo, "Webhook channel must not contain an email recipient");
            }
            case EMAIL -> {
                this.botTokenCiphertext = absent(botTokenCiphertext, "Email channel must not contain a Telegram token");
                this.chatId = absent(chatId, "Email channel must not contain a Telegram chat id");
                this.webhookUrlCiphertext = absent(webhookUrlCiphertext, "Email channel must not contain a webhook URL");
                this.emailTo = email(emailTo);
            }
            default -> throw new IllegalArgumentException("Unsupported notification channel type: " + type);
        }
    }

    /** Validates a plaintext URL before it is encrypted by the application layer. */
    public static String validateWebhookUrl(String value, boolean slack) {
        String candidate = text(value, slack ? "Slack webhook URL" : "Webhook URL", 4096);
        URI uri;
        try { uri = URI.create(candidate); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Webhook URL is not valid"); }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException("Webhook URL must use HTTP or HTTPS and include a host");
        }
        if (slack && (!"https".equalsIgnoreCase(uri.getScheme())
                || !("hooks.slack.com".equalsIgnoreCase(uri.getHost())
                || "hooks.slack-gov.com".equalsIgnoreCase(uri.getHost())))) {
            throw new IllegalArgumentException("Slack webhook must use an official HTTPS host");
        }
        return candidate;
    }

    private static String email(String value) {
        String candidate = text(value, "Email recipient", 255);
        if (!candidate.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new IllegalArgumentException("Email recipient is not valid");
        }
        return candidate;
    }
    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
        String result = value.strip();
        if (result.length() > max) throw new IllegalArgumentException(label + " must be at most " + max + " characters");
        return result;
    }
    private static String absent(String value, String message) {
        if (value != null && !value.isBlank()) throw new IllegalArgumentException(message);
        return null;
    }
    private static <T> T require(T value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
        return value;
    }
}
