package com.hoangluongtran0309.dbbackup.application.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.exception.NotificationChannelInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannelType;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

@ExtendWith(MockitoExtension.class)
class ManageNotificationChannelServiceTest {
    private static final Instant NOW = Instant.parse("2026-09-24T01:00:00Z");
    private static final UUID ID = UUID.randomUUID();

    @Mock NotificationChannelRepository channels;
    @Mock TargetNotificationSubscriptionRepository subscriptions;
    @Mock EncryptionPort encryption;
    @Mock NotificationDispatcher dispatcher;
    private ManageNotificationChannelService service;

    @BeforeEach void setUp() {
        service = new ManageNotificationChannelService(channels, subscriptions,
                new NotificationCredentials(encryption), dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().when(channels.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test void encryptsTelegramTokenBeforeSaving() {
        when(encryption.encrypt("123:secret")).thenReturn("sealed-token");

        NotificationChannel created = service.create(new SaveNotificationChannelCommand(
                "On call", NotificationChannelType.TELEGRAM, " 123:secret ", "-10001", null, null));

        assertThat(created.getBotTokenCiphertext()).isEqualTo("sealed-token");
        assertThat(created.getBotTokenCiphertext()).doesNotContain("secret");
        verify(encryption).encrypt("123:secret");
    }

    @Test void blankSecretOnEditKeepsExistingCiphertext() {
        ConnectionCheck previousTest = ConnectionCheck.failed("HTTP 503", NOW.minusSeconds(60));
        NotificationChannel current = webhook(NotificationChannelType.WEBHOOK, "sealed-existing")
                .toBuilder().lastConnectionCheck(previousTest).build();
        when(channels.findById(ID)).thenReturn(Optional.of(current));

        NotificationChannel edited = service.edit(ID, new SaveNotificationChannelCommand(
                "Renamed", NotificationChannelType.WEBHOOK, null, null, "  ", null));

        assertThat(edited.getWebhookUrlCiphertext()).isEqualTo("sealed-existing");
        assertThat(edited.getLastConnectionCheck()).isEqualTo(previousTest);
        verify(encryption, never()).encrypt(any());
    }

    @Test void typeCannotChangeAfterCreation() {
        when(channels.findById(ID)).thenReturn(Optional.of(
                webhook(NotificationChannelType.WEBHOOK, "sealed-existing")));

        assertThatThrownBy(() -> service.edit(ID, new SaveNotificationChannelCommand(
                "Changed", NotificationChannelType.EMAIL, null, null, null, "ops@example.com")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be changed");
        verify(channels, never()).save(any());
    }

    @Test void refusesToDeleteAChannelStillUsedByATarget() {
        when(channels.findById(ID)).thenReturn(Optional.of(
                webhook(NotificationChannelType.WEBHOOK, "sealed-existing")));
        when(subscriptions.countForChannel(ID)).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(ID))
                .isInstanceOf(NotificationChannelInUseException.class);
        verify(channels, never()).deleteById(any());
    }

    @Test void testSendsARealMessageAndPersistsTheOutcome() {
        NotificationChannel channel = webhook(NotificationChannelType.WEBHOOK, "sealed-existing");
        when(channels.findById(ID)).thenReturn(Optional.of(channel));

        ConnectionCheck result = service.test(ID);

        assertThat(result.successful()).isTrue();
        verify(dispatcher).sendDirect(org.mockito.ArgumentMatchers.eq(channel), any());
        ArgumentCaptor<ConnectionCheck> saved = ArgumentCaptor.forClass(ConnectionCheck.class);
        verify(channels).recordConnectionCheck(org.mockito.ArgumentMatchers.eq(ID), saved.capture());
        assertThat(saved.getValue().message()).isEqualTo("Test message sent");
    }

    @Test void failedTestPersistsASanitizedReason() {
        NotificationChannel channel = webhook(NotificationChannelType.WEBHOOK, "sealed-existing");
        when(channels.findById(ID)).thenReturn(Optional.of(channel));
        doThrow(new IllegalStateException("token=must-not-leak"))
                .when(dispatcher).sendDirect(org.mockito.ArgumentMatchers.eq(channel), any());

        ConnectionCheck result = service.test(ID);

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("[REDACTED]").doesNotContain("must-not-leak");
        verify(channels).recordConnectionCheck(ID, result);
    }

    private static NotificationChannel webhook(NotificationChannelType type, String ciphertext) {
        return NotificationChannel.builder().id(ID).name("Automation").type(type)
                .webhookUrlCiphertext(ciphertext).createdAt(NOW).updatedAt(NOW).build();
    }
}
