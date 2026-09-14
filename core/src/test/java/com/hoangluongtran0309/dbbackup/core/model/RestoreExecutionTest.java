package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class RestoreExecutionTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-09T10:04:00Z");

    private static RestoreExecution running() {
        return RestoreExecution.started(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), STARTED);
    }

    @Test
    void startsRunningAgainstOneBackupIntoOneTarget() {
        UUID backupId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        RestoreExecution execution = RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED);

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.getBackupExecutionId()).isEqualTo(backupId);
        assertThat(execution.getTargetId()).isEqualTo(targetId);
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.getErrorMessage()).isNull();
    }

    @Test
    void aRestoreMustSayWhereItsDataGoes() {
        assertThatThrownBy(() -> RestoreExecution.started(UUID.randomUUID(), UUID.randomUUID(), null, STARTED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target id");
    }

    @Test
    void succeedingKeepsIdentityAndRecordsTheFinishTime() {
        RestoreExecution started = running();

        RestoreExecution done = started.succeeded(FINISHED);

        assertThat(done.getId()).isEqualTo(started.getId());
        assertThat(done.getBackupExecutionId()).isEqualTo(started.getBackupExecutionId());
        assertThat(done.getTargetId()).isEqualTo(started.getTargetId());
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(done.getFinishedAt()).isEqualTo(FINISHED);
        assertThat(done.getErrorMessage()).isNull();
    }

    @Test
    void failingRecordsTheReason() {
        RestoreExecution done = running().failed("mysql exited with 1: Access denied", FINISHED);

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(done.getErrorMessage()).isEqualTo("mysql exited with 1: Access denied");
    }

    @Test
    void failingWithNothingToSayStillSaysSomething() {
        assertThat(running().failed("  ", FINISHED).getErrorMessage())
                .isEqualTo("Restore failed, with no reason reported");
    }

    @Test
    void aFinishedRestoreCannotBeFinishedAgain() {
        RestoreExecution done = running().succeeded(FINISHED);

        assertThatThrownBy(() -> done.failed("too late", FINISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished as SUCCEEDED");
        assertThatThrownBy(() -> done.succeeded(FINISHED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesAStatusThatDisagreesWithTheFinishTimestamp() {
        assertThatThrownBy(() -> RestoreExecution.builder()
                .id(UUID.randomUUID()).backupExecutionId(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(ExecutionStatus.SUCCEEDED).startedAt(STARTED)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a finish timestamp");

        assertThatThrownBy(() -> RestoreExecution.builder()
                .id(UUID.randomUUID()).backupExecutionId(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(ExecutionStatus.RUNNING).startedAt(STARTED).finishedAt(FINISHED)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesAnOverlongErrorToTheColumnWidth() {
        assertThat(running().failed("x".repeat(5000), FINISHED).getErrorMessage())
                .hasSize(RestoreExecution.MAX_ERROR_LENGTH);
    }

    @Test
    void durationRunsToTheFinishOrToNow() {
        assertThat(running().succeeded(FINISHED).duration(Instant.MAX)).isEqualTo(Duration.ofMinutes(4));
        assertThat(running().duration(STARTED.plusSeconds(30))).isEqualTo(Duration.ofSeconds(30));
    }
}
