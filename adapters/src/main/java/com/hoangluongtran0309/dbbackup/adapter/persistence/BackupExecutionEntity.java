package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;

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
@Table(name = "backup_executions")
@Getter
@Setter
@NoArgsConstructor
class BackupExecutionEntity {

    @Id
    private UUID id;

    @Column(name = "target_id", nullable = false)
    private UUID targetId;

    // STRING, not ORDINAL: an ordinal turns a reordering of the enum into a
    // silent rewrite of every historical row.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ExecutionStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "artifact_path", columnDefinition = "text")
    private String artifactPath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;
}
