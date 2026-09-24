package com.hoangluongtran0309.dbbackup.core.model;

import java.util.Set;
import java.util.UUID;

/** One target's subscription to one reusable channel. */
public record TargetNotificationSubscription(UUID channelId, Set<NotificationEventType> events) {
    public TargetNotificationSubscription {
        if (channelId == null) throw new IllegalArgumentException("Channel id is required");
        events = events == null ? Set.of() : Set.copyOf(events);
        if (events.isEmpty()) throw new IllegalArgumentException("Choose at least one notification event");
        if (events.stream().anyMatch(event -> !event.isSubscribable())) {
            throw new IllegalArgumentException("TEST cannot be used as a target notification event");
        }
    }
}
