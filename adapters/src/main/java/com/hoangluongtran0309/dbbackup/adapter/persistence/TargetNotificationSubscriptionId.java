package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
class TargetNotificationSubscriptionId implements Serializable {
    @Column(name = "target_id") private UUID targetId;
    @Column(name = "channel_id") private UUID channelId;
}
