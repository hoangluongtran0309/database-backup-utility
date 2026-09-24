package com.hoangluongtran0309.dbbackup.adapter.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "database_target_notification_channels")
@Getter @Setter @NoArgsConstructor
class TargetNotificationSubscriptionEntity {
    @EmbeddedId private TargetNotificationSubscriptionId id;
    @Column(nullable = false, columnDefinition = "text") private String events;
}
