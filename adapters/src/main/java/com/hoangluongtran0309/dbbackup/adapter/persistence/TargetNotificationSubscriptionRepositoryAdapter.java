package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.model.NotificationEventType;
import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;
import com.hoangluongtran0309.dbbackup.core.port.TargetNotificationSubscriptionRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class TargetNotificationSubscriptionRepositoryAdapter implements TargetNotificationSubscriptionRepository {
    private final TargetNotificationSubscriptionJpaRepository repository;

    @Override public List<TargetNotificationSubscription> findByTargetId(UUID targetId) {
        return repository.findByIdTargetId(targetId).stream()
                .sorted(Comparator.comparing(entity -> entity.getId().getChannelId()))
                .map(entity -> new TargetNotificationSubscription(entity.getId().getChannelId(),
                        Arrays.stream(entity.getEvents().split(","))
                                .map(NotificationEventType::valueOf)
                                .collect(Collectors.toCollection(() -> EnumSet.noneOf(NotificationEventType.class)))))
                .toList();
    }
    @Override public long countForChannel(UUID channelId) { return repository.countByIdChannelId(channelId); }

    @Override @Transactional
    public void replaceForTarget(UUID targetId, List<TargetNotificationSubscription> subscriptions) {
        repository.deleteByIdTargetId(targetId);
        repository.flush();
        repository.saveAll(subscriptions.stream().map(subscription -> {
            TargetNotificationSubscriptionEntity entity = new TargetNotificationSubscriptionEntity();
            entity.setId(new TargetNotificationSubscriptionId(targetId, subscription.channelId()));
            entity.setEvents(subscription.events().stream().sorted().map(Enum::name).collect(Collectors.joining(",")));
            return entity;
        }).toList());
    }
}
