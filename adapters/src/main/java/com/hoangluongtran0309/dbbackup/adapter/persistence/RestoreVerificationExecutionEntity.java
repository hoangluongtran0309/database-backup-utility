package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "restore_verification_executions")
@Getter
@Setter
@NoArgsConstructor
class RestoreVerificationExecutionEntity {
    @Id private UUID id;
    @Column(name = "backup_execution_id", nullable = false) private UUID backupExecutionId;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private RestoreVerificationStatus status;
    @Column(name = "started_at", nullable = false) private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "checked_objects") private Integer checkedObjects;
    @Column(name = "result_summary", length = 2000) private String resultSummary;
    @Column(name = "error_message", length = 2000) private String errorMessage;
}
