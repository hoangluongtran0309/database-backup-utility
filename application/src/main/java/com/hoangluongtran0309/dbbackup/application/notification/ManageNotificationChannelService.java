package com.hoangluongtran0309.dbbackup.application.notification;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.exception.NotificationChannelInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManageNotificationChannelService {
    private final NotificationChannelRepository channels;
    private final TargetNotificationSubscriptionRepository subscriptions;
    private final NotificationCredentials credentials;
    private final NotificationDispatcher dispatcher;
    private final Clock clock;

    public List<NotificationChannel> listAll() { return channels.findAll(); }
    public NotificationChannel get(UUID id) { return channels.findById(id).orElseThrow(
            () -> new NoSuchElementException("No notification channel with id " + id)); }

    public NotificationChannel create(SaveNotificationChannelCommand command) {
        Instant now = clock.instant();
        return channels.save(build(UUID.randomUUID(), now, now, null, command, null));
    }

    public NotificationChannel edit(UUID id, SaveNotificationChannelCommand command) {
        NotificationChannel current = get(id);
        if (current.getType() != command.type()) {
            throw new IllegalArgumentException("Notification channel type cannot be changed");
        }
        return channels.save(build(id, current.getCreatedAt(), clock.instant(),
                current.getLastConnectionCheck(), command, current));
    }

    public ConnectionCheck test(UUID id) {
        NotificationChannel channel = get(id);
        ConnectionCheck result;
        try {
            dispatcher.sendDirect(channel, NotificationMessage.test(channel.getId(), channel.getName(), clock.instant()));
            result = new ConnectionCheck(true, "Test message sent", clock.instant());
        } catch (RuntimeException e) {
            result = ConnectionCheck.failed(safeMessage(e), clock.instant());
        }
        channels.recordConnectionCheck(id, result);
        return result;
    }

    public void delete(UUID id) {
        NotificationChannel channel = channels.findById(id).orElse(null);
        if (channel == null) return;
        if (subscriptions.countForChannel(id) > 0) throw new NotificationChannelInUseException(channel.getName());
        channels.deleteById(id);
    }

    private NotificationChannel build(UUID id, Instant createdAt, Instant updatedAt, ConnectionCheck check,
            SaveNotificationChannelCommand command, NotificationChannel current) {
        if (command.type() == null) throw new IllegalArgumentException("Channel type is required");
        String botToken = null;
        String webhook = null;
        String chatId = null;
        String emailTo = null;
        switch (command.type()) {
            case TELEGRAM -> {
                chatId = command.chatId();
                if (command.suppliesBotToken()) botToken = credentials.encrypt(command.botToken().strip());
                else if (current != null) botToken = current.getBotTokenCiphertext();
            }
            case SLACK, WEBHOOK -> {
                if (command.suppliesWebhookUrl()) {
                    String valid = NotificationChannel.validateWebhookUrl(
                            command.webhookUrl(), command.type() == NotificationChannelType.SLACK);
                    webhook = credentials.encrypt(valid);
                } else if (current != null) webhook = current.getWebhookUrlCiphertext();
            }
            case EMAIL -> emailTo = command.emailTo();
            default -> throw new IllegalArgumentException("Unsupported notification channel type");
        }
        return NotificationChannel.builder().id(id).name(command.name()).type(command.type())
                .botTokenCiphertext(botToken).chatId(chatId).webhookUrlCiphertext(webhook).emailTo(emailTo)
                .createdAt(createdAt).updatedAt(updatedAt).lastConnectionCheck(check).build();
    }

    private static String safeMessage(RuntimeException error) {
        String message = NotificationDispatcher.sanitize(error.getMessage());
        if (message == null || message.isBlank()) return "Notification test failed";
        return message.length() <= ConnectionCheck.MAX_MESSAGE_LENGTH
                ? message : message.substring(0, ConnectionCheck.MAX_MESSAGE_LENGTH);
    }
}
