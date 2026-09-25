package com.hoangluongtran0309.dbbackup.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.EmailNotificationSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelSettings;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.NotificationMessage;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;
import com.hoangluongtran0309.dbbackup.core.port.NotificationPort;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

class NotificationDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test void failureOfOneChannelDoesNotBlockTheNext() {
        RecordingPort port = new RecordingPort(true);
        UUID firstId = UUID.randomUUID(); UUID secondId = UUID.randomUUID();
        InMemoryChannels channels = new InMemoryChannels(channel(firstId), channel(secondId));
        InMemorySubscriptions links = new InMemorySubscriptions(List.of(
                new TargetNotificationSubscription(firstId, Set.of(NotificationEventType.BACKUP_FAILED)),
                new TargetNotificationSubscription(secondId, Set.of(NotificationEventType.BACKUP_FAILED))));
        NotificationDispatcher dispatcher = dispatcher(List.of(port), links, channels);

        dispatcher.publishBackup(target(), BackupExecution.started(UUID.randomUUID(), target().getId(), NOW)
                .failed("boom", NOW.plusSeconds(1)), NotificationEventType.BACKUP_FAILED);

        assertThat(port.calls).isEqualTo(2);
    }

    @Test void ignoresEventsNotSelectedByTheTarget() {
        RecordingPort port = new RecordingPort(false);
        UUID channelId = UUID.randomUUID();
        NotificationDispatcher dispatcher = dispatcher(List.of(port),
                new InMemorySubscriptions(List.of(new TargetNotificationSubscription(
                        channelId, Set.of(NotificationEventType.BACKUP_FAILED)))),
                new InMemoryChannels(channel(channelId)));
        DatabaseTarget target = target();
        dispatcher.publishBackup(target, BackupExecution.started(UUID.randomUUID(), target.getId(), NOW),
                NotificationEventType.BACKUP_STARTED);
        assertThat(port.calls).isZero();
    }

    @Test void redactsSecretsBeforeTheyReachTheAdapter() {
        RecordingPort port = new RecordingPort(false);
        UUID channelId = UUID.randomUUID();
        NotificationDispatcher dispatcher = dispatcher(List.of(port),
                new InMemorySubscriptions(List.of(new TargetNotificationSubscription(
                        channelId, Set.of(NotificationEventType.BACKUP_FAILED)))),
                new InMemoryChannels(channel(channelId)));
        DatabaseTarget target = target();
        dispatcher.publishBackup(target, BackupExecution.started(UUID.randomUUID(), target.getId(), NOW)
                .failed("password=hunter2 postgres://admin:secret@db/prod Authorization: Bearer abc.def", NOW),
                NotificationEventType.BACKUP_FAILED);
        assertThat(port.last.errorMessage()).doesNotContain("hunter2", "secret", "abc.def")
                .contains("[REDACTED]");
    }

    @Test void verificationEventIsFilteredAndCarriesBothAttemptAndBackupIds() {
        RecordingPort port = new RecordingPort(false);
        UUID channelId = UUID.randomUUID();
        NotificationDispatcher dispatcher = dispatcher(List.of(port),
                new InMemorySubscriptions(List.of(new TargetNotificationSubscription(
                        channelId, Set.of(NotificationEventType.VERIFICATION_SUCCESS)))),
                new InMemoryChannels(channel(channelId)));
        UUID backupId = UUID.randomUUID();
        UUID verificationId = UUID.randomUUID();
        RestoreVerificationExecution execution = RestoreVerificationExecution
                .started(verificationId, backupId, NOW)
                .succeeded(new RestoreVerificationResult(2, "healthy"), NOW.plusSeconds(1));

        dispatcher.publishVerification(target(), execution, NotificationEventType.VERIFICATION_SUCCESS);

        assertThat(port.calls).isOne();
        assertThat(port.last.backupExecutionId()).isEqualTo(backupId);
        assertThat(port.last.verificationExecutionId()).isEqualTo(verificationId);
    }

    @Test void reportsAMissingAdapterClearly() {
        NotificationDispatcher dispatcher = dispatcher(List.of(), new InMemorySubscriptions(List.of()),
                new InMemoryChannels());
        assertThatThrownBy(() -> dispatcher.sendDirect(channel(UUID.randomUUID()),
                NotificationMessage.test(UUID.randomUUID(), "test", NOW)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No notification adapter registered for EMAIL");
    }

    private static NotificationDispatcher dispatcher(List<NotificationPort> ports,
            TargetNotificationSubscriptionRepository links, NotificationChannelRepository channels) {
        EncryptionPort identity = new EncryptionPort() {
            @Override public String encrypt(String value) { return value; }
            @Override public String decrypt(String value) { return value; }
        };
        return new NotificationDispatcher(ports, links, channels, new NotificationCredentials(identity), CLOCK);
    }
    private static NotificationChannel channel(UUID id) {
        return NotificationChannel.builder().id(id).name("email-" + id).type(NotificationChannelType.EMAIL)
                .emailTo("ops@example.com").createdAt(NOW).updatedAt(NOW).build();
    }
    private static DatabaseTarget target() {
        return DatabaseTarget.builder().id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .name("production").engine(DatabaseEngine.MYSQL).host("db").port(3306)
                .databaseName("shop").username("backup").passwordCiphertext("sealed").createdAt(NOW).build();
    }
    private static final class RecordingPort implements NotificationPort {
        private final boolean failFirst; private int calls; private NotificationMessage last;
        RecordingPort(boolean failFirst) { this.failFirst = failFirst; }
        @Override public NotificationChannelType supportedType() { return NotificationChannelType.EMAIL; }
        @Override public void send(NotificationChannelSettings settings, NotificationMessage message) {
            assertThat(settings).isInstanceOf(EmailNotificationSettings.class);
            calls++; last = message; if (failFirst && calls == 1) throw new IllegalStateException("unavailable");
        }
    }
    private static final class InMemoryChannels implements NotificationChannelRepository {
        private final Map<UUID, NotificationChannel> values = new HashMap<>();
        InMemoryChannels(NotificationChannel... channels) { for (var channel : channels) values.put(channel.getId(), channel); }
        @Override public Optional<NotificationChannel> findById(UUID id) { return Optional.ofNullable(values.get(id)); }
        @Override public NotificationChannel save(NotificationChannel channel) { throw new UnsupportedOperationException(); }
        @Override public List<NotificationChannel> findAll() { return List.copyOf(values.values()); }
        @Override public void recordConnectionCheck(UUID id, ConnectionCheck check) { throw new UnsupportedOperationException(); }
        @Override public void deleteById(UUID id) { throw new UnsupportedOperationException(); }
    }
    private record InMemorySubscriptions(List<TargetNotificationSubscription> values)
            implements TargetNotificationSubscriptionRepository {
        @Override public List<TargetNotificationSubscription> findByTargetId(UUID id) { return values; }
        @Override public long countForChannel(UUID id) { return 0; }
        @Override public void replaceForTarget(UUID id, List<TargetNotificationSubscription> subscriptions) { }
    }
}
