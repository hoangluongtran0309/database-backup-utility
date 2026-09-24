package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface TargetNotificationSubscriptionJpaRepository
        extends JpaRepository<TargetNotificationSubscriptionEntity, TargetNotificationSubscriptionId> {
    List<TargetNotificationSubscriptionEntity> findByIdTargetId(UUID targetId);
    long countByIdChannelId(UUID channelId);
    void deleteByIdTargetId(UUID targetId);
}
