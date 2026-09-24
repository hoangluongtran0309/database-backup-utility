package com.hoangluongtran0309.dbbackup.application.notification;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

@ExtendWith(MockitoExtension.class)
class ManageTargetNotificationServiceTest {
    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final UUID CHANNEL_ID = UUID.randomUUID();
    @Mock TargetNotificationSubscriptionRepository subscriptions;
    @Mock DatabaseTargetRepository targets;
    @Mock NotificationChannelRepository channels;
    private ManageTargetNotificationService service;

    @BeforeEach void setUp() {
        service = new ManageTargetNotificationService(subscriptions, targets, channels);
        org.mockito.Mockito.lenient().when(targets.findById(TARGET_ID))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(
                        com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget.class)));
    }

    @Test void refusesAChannelThatNoLongerExists() {
        when(channels.findById(CHANNEL_ID)).thenReturn(Optional.empty());
        TargetNotificationSubscription requested = new TargetNotificationSubscription(
                CHANNEL_ID, Set.of(NotificationEventType.BACKUP_FAILED));

        assertThatThrownBy(() -> service.replace(TARGET_ID, List.of(requested)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("existing notification channel");
        verify(subscriptions, never()).replaceForTarget(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test void refusesTheSameChannelTwice() {
        TargetNotificationSubscription requested = new TargetNotificationSubscription(
                CHANNEL_ID, Set.of(NotificationEventType.BACKUP_FAILED));
        when(channels.findById(CHANNEL_ID)).thenReturn(Optional.of(org.mockito.Mockito.mock(
                com.hoangluongtran0309.dbbackup.core.model.NotificationChannel.class)));

        assertThatThrownBy(() -> service.replace(TARGET_ID, List.of(requested, requested)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("only be selected once");
    }
}
