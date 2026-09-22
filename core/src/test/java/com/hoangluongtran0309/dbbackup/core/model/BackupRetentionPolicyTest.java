package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidRetentionPolicyException;

class BackupRetentionPolicyTest {

    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Instant CREATED = Instant.parse("2026-09-22T01:00:00Z");

    @Test
    void createsAPolicyWithoutARunOutcome() {
        BackupRetentionPolicy policy = BackupRetentionPolicy.create(TARGET_ID, 7, CREATED);

        assertThat(policy.getTargetId()).isEqualTo(TARGET_ID);
        assertThat(policy.getKeepSuccessful()).isEqualTo(7);
        assertThat(policy.getLastRunAt()).isNull();
        assertThat(policy.getLastDeletedCount()).isNull();
        assertThat(policy.lastRunFailed()).isFalse();
    }

    @Test
    void requiresAtLeastOneSuccessfulBackupToRemain() {
        assertThatThrownBy(() -> BackupRetentionPolicy.create(TARGET_ID, 0, CREATED))
                .isInstanceOf(InvalidRetentionPolicyException.class)
                .extracting("field")
                .isEqualTo("keepSuccessful");
    }

    @Test
    void editingResetsTheOutcomeForTheNewRule() {
        BackupRetentionPolicy completed = BackupRetentionPolicy.create(TARGET_ID, 7, CREATED)
                .completed(3, null, CREATED.plusSeconds(60));

        BackupRetentionPolicy edited = completed.edited(14, CREATED.plusSeconds(120));

        assertThat(edited.getKeepSuccessful()).isEqualTo(14);
        assertThat(edited.getCreatedAt()).isEqualTo(CREATED);
        assertThat(edited.getLastRunAt()).isNull();
        assertThat(edited.getLastDeletedCount()).isNull();
    }

    @Test
    void recordsAndTruncatesAFailedRun() {
        String longError = "x".repeat(BackupRetentionPolicy.MAX_ERROR_LENGTH + 50);

        BackupRetentionPolicy failed = BackupRetentionPolicy.create(TARGET_ID, 3, CREATED)
                .completed(2, longError, CREATED.plusSeconds(60));

        assertThat(failed.lastRunFailed()).isTrue();
        assertThat(failed.getLastDeletedCount()).isEqualTo(2);
        assertThat(failed.getLastError()).hasSize(BackupRetentionPolicy.MAX_ERROR_LENGTH);
    }

    @Test
    void refusesHalfOfARunResult() {
        assertThatThrownBy(() -> BackupRetentionPolicy.builder()
                .targetId(TARGET_ID)
                .keepSuccessful(3)
                .createdAt(CREATED)
                .updatedAt(CREATED)
                .lastRunAt(CREATED.plusSeconds(1))
                .build())
                .isInstanceOf(InvalidRetentionPolicyException.class);
    }
}
