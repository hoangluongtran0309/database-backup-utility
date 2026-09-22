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
@Table(name = "backup_retention_policies")
@Getter
@Setter
@NoArgsConstructor
class BackupRetentionPolicyEntity {

    @Id
    @Column(name = "target_id")
    private UUID targetId;

    @Column(name = "keep_successful", nullable = false)
    private int keepSuccessful;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "last_deleted_count")
    private Integer lastDeletedCount;

    @Column(name = "last_error", length = 2000)
    private String lastError;
}
