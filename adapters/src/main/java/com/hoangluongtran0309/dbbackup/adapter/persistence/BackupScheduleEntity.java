package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "backup_schedules")
@Getter
@Setter
@NoArgsConstructor
class BackupScheduleEntity {

    @Id
    private UUID id;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "cron_expression", nullable = false, length = 120)
    private String cronExpression;

    @Column(name = "zone_id", nullable = false, length = 64)
    private String zoneId;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
