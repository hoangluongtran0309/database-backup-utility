package com.hoangluongtran0309.dbbackup.application.notification;

import java.util.HashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ManageTargetNotificationService {
    private final TargetNotificationSubscriptionRepository subscriptions;
    private final DatabaseTargetRepository targets;
    private final NotificationChannelRepository channels;

    public List<TargetNotificationSubscription> subscriptionsFor(UUID targetId) {
        requireTarget(targetId);
        return subscriptions.findByTargetId(targetId);
    }

    public void replace(UUID targetId, List<TargetNotificationSubscription> requested) {
        requireTarget(targetId);
        List<TargetNotificationSubscription> values = requested == null ? List.of() : List.copyOf(requested);
        HashSet<UUID> ids = new HashSet<>();
        for (TargetNotificationSubscription subscription : values) {
            if (!ids.add(subscription.channelId())) {
                throw new IllegalArgumentException("A notification channel can only be selected once");
            }
            if (channels.findById(subscription.channelId()).isEmpty()) {
                throw new IllegalArgumentException("Choose an existing notification channel");
            }
        }
        subscriptions.replaceForTarget(targetId, values);
    }

    private void requireTarget(UUID id) {
        if (id == null || targets.findById(id).isEmpty()) {
            throw new NoSuchElementException("No database target with id " + id);
        }
    }
}
