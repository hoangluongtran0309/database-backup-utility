package com.hoangluongtran0309.dbbackup.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.application.backup.BackupActivityGuard;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService.RetentionDeletion;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.BackupRetentionPolicyRepository;

@ExtendWith(MockitoExtension.class)
class ApplyBackupRetentionServiceTest {

    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");
    private static final Instant CONFIGURED = NOW.minusSeconds(3600);

    @Mock private BackupRetentionPolicyRepository policies;
    @Mock private BackupExecutionRepository backups;
    @Mock private BackupArtifactService artifacts;

    private ApplyBackupRetentionService service;

    @BeforeEach
    void setUp() {
        service = new ApplyBackupRetentionService(
                policies,
                backups,
                artifacts,
                new BackupActivityGuard(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void doesNothingWhenRetentionIsDisabled() {
        when(policies.findByTargetId(TARGET_ID)).thenReturn(Optional.empty());

        assertThat(service.applyAfterSuccessfulBackup(TARGET_ID)).isEmpty();

        verifyNoInteractions(backups, artifacts);
    }

    @Test
    void deletesCandidatesAndRecordsTheOutcome() {
        BackupExecution first = backup(NOW.minusSeconds(300));
        BackupExecution second = backup(NOW.minusSeconds(600));
        givenPolicy(3);
        when(backups.findRetentionCandidates(TARGET_ID, 3)).thenReturn(List.of(first, second));
        when(artifacts.deleteForRetention(first.getId())).thenReturn(RetentionDeletion.DELETED);
        when(artifacts.deleteForRetention(second.getId())).thenReturn(RetentionDeletion.ALREADY_GONE);
        when(policies.recordRunResult(TARGET_ID, CONFIGURED, NOW, 1, null)).thenReturn(true);

        var result = service.applyAfterSuccessfulBackup(TARGET_ID).orElseThrow();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.successful()).isTrue();
        verify(policies).recordRunResult(TARGET_ID, CONFIGURED, NOW, 1, null);
    }

    @Test
    void aRestoreAddedAfterSelectionProtectsTheBackup() {
        BackupExecution candidate = backup(NOW.minusSeconds(300));
        givenPolicy(2);
        when(backups.findRetentionCandidates(TARGET_ID, 2)).thenReturn(List.of(candidate));
        when(artifacts.deleteForRetention(candidate.getId())).thenReturn(RetentionDeletion.PROTECTED);
        when(policies.recordRunResult(TARGET_ID, CONFIGURED, NOW, 0, null)).thenReturn(true);

        var result = service.applyAfterSuccessfulBackup(TARGET_ID).orElseThrow();

        assertThat(result.deleted()).isZero();
        assertThat(result.protectedSinceSelection()).isEqualTo(1);
    }

    @Test
    void stopsAtTheFirstFailureAndRecordsPartialProgress() {
        BackupExecution first = backup(NOW.minusSeconds(300));
        BackupExecution broken = backup(NOW.minusSeconds(600));
        BackupExecution untouched = backup(NOW.minusSeconds(900));
        givenPolicy(1);
        when(backups.findRetentionCandidates(TARGET_ID, 1))
                .thenReturn(List.of(first, broken, untouched));
        when(artifacts.deleteForRetention(first.getId())).thenReturn(RetentionDeletion.DELETED);
        when(artifacts.deleteForRetention(broken.getId()))
                .thenThrow(new IllegalStateException("permission denied"));
        when(policies.recordRunResult(TARGET_ID, CONFIGURED, NOW, 1, "permission denied"))
                .thenReturn(true);

        var result = service.applyAfterSuccessfulBackup(TARGET_ID).orElseThrow();

        assertThat(result.deleted()).isEqualTo(1);
        assertThat(result.error()).isEqualTo("permission denied");
        verify(artifacts, never()).deleteForRetention(untouched.getId());
    }

    @Test
    void doesNotRecreateOrOverwriteAChangedPolicy() {
        givenPolicy(3);
        when(backups.findRetentionCandidates(TARGET_ID, 3)).thenReturn(List.of());
        when(policies.recordRunResult(eq(TARGET_ID), eq(CONFIGURED), eq(NOW), eq(0), any()))
                .thenReturn(false);

        service.applyAfterSuccessfulBackup(TARGET_ID);

        verify(policies).recordRunResult(TARGET_ID, CONFIGURED, NOW, 0, null);
        verify(policies, never()).save(any());
    }

    private void givenPolicy(int keep) {
        when(policies.findByTargetId(TARGET_ID)).thenReturn(Optional.of(
                BackupRetentionPolicy.create(TARGET_ID, keep, CONFIGURED)));
    }

    private static BackupExecution backup(Instant startedAt) {
        return BackupExecution.started(UUID.randomUUID(), TARGET_ID, startedAt)
                .succeeded("/backups/" + UUID.randomUUID(), 100, "a".repeat(64), startedAt.plusSeconds(30));
    }
}
