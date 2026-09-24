package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.TargetNotificationSubscription;

public interface TargetNotificationSubscriptionRepository {
    List<TargetNotificationSubscription> findByTargetId(UUID targetId);
    long countForChannel(UUID channelId);
    void replaceForTarget(UUID targetId, List<TargetNotificationSubscription> subscriptions);
}
