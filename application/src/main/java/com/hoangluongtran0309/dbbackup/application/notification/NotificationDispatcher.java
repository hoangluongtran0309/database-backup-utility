package com.hoangluongtran0309.dbbackup.application.notification;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;
import com.hoangluongtran0309.dbbackup.core.port.NotificationPort;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

@Component
public class NotificationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final Pattern KEY_VALUE_SECRET = Pattern.compile(
            "(?i)(password|passwd|pwd|token|secret|api[-_]?key|authorization)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern URI_PASSWORD = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://[^\\s:/@]+:)[^\\s@/]+@");
    private static final Pattern BEARER_TOKEN = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");

    private final Map<NotificationChannelType, NotificationPort> adapters =
            new EnumMap<>(NotificationChannelType.class);
    private final TargetNotificationSubscriptionRepository subscriptions;
    private final NotificationChannelRepository channels;
    private final NotificationCredentials credentials;
    private final Clock clock;

    public NotificationDispatcher(List<NotificationPort> ports,
            TargetNotificationSubscriptionRepository subscriptions,
            NotificationChannelRepository channels, NotificationCredentials credentials, Clock clock) {
        this.subscriptions = subscriptions;
        this.channels = channels;
        this.credentials = credentials;
        this.clock = clock;
        for (NotificationPort port : ports) {
            if (adapters.put(port.supportedType(), port) != null) {
                throw new IllegalStateException("Two notification adapters claim " + port.supportedType());
            }
        }
    }

    public void publishBackup(DatabaseTarget target, BackupExecution execution, NotificationEventType event) {
        publish(target.getId(), new NotificationMessage(event, clock.instant(),
                target.getId(), target.getName(), target.getEngine(), null, null, null,
                execution.getId(), null, execution.getStatus().name(), sanitize(execution.getErrorMessage()),
                null, null));
    }

    public void publishRestore(DatabaseTarget source, DatabaseTarget destination,
            RestoreExecution execution, NotificationEventType event) {
        publish(destination.getId(), new NotificationMessage(event, clock.instant(),
                source.getId(), source.getName(), source.getEngine(),
                destination.getId(), destination.getName(), destination.getEngine(),
                execution.getBackupExecutionId(), execution.getId(), execution.getStatus().name(),
                sanitize(execution.getErrorMessage()), null, null));
    }

    private void publish(UUID targetId, NotificationMessage message) {
        try {
            for (var subscription : subscriptions.findByTargetId(targetId)) {
                if (!subscription.events().contains(message.event())) continue;
                channels.findById(subscription.channelId()).ifPresentOrElse(
                        channel -> send(channel, message),
                        () -> log.warn("Target {} references missing notification channel {}",
                                targetId, subscription.channelId()));
            }
        } catch (RuntimeException e) {
            log.warn("Could not read notification subscriptions for target {}", targetId, e);
        }
    }

    private void send(NotificationChannel channel, NotificationMessage message) {
        try { sendDirect(channel, message); }
        catch (RuntimeException e) {
            log.warn("Notification delivery failed for channel {} ({})", channel.getId(), channel.getType(), e);
        }
    }

    public void sendDirect(NotificationChannel channel, NotificationMessage message) {
        NotificationPort adapter = adapters.get(channel.getType());
        if (adapter == null) throw new IllegalStateException("No notification adapter registered for " + channel.getType());
        adapter.send(credentials.decrypt(channel), message);
    }

    static String sanitize(String value) {
        if (value == null) return null;
        String compact = value.replaceAll("[\\r\\n]+", " ").strip();
        compact = URI_PASSWORD.matcher(compact).replaceAll("$1[REDACTED]@");
        compact = BEARER_TOKEN.matcher(compact).replaceAll("$1[REDACTED]");
        compact = KEY_VALUE_SECRET.matcher(compact).replaceAll("$1$2[REDACTED]");
        return compact.length() <= 2000 ? compact : compact.substring(0, 2000);
    }
}
