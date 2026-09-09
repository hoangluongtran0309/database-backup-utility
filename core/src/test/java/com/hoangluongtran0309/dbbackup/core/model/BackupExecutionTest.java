package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BackupExecutionTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-09-09T10:02:30Z");

    private static BackupExecution running() {
        return BackupExecution.started(UUID.randomUUID(), UUID.randomUUID(), STARTED);
    }

    @Test
    void startsRunningWithNothingRecordedYet() {
        BackupExecution execution = running();

        assertThat(execution.getStatus()).isEqualTo(BackupStatus.RUNNING);
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.getArtifactPath()).isNull();
        assertThat(execution.getSizeBytes()).isNull();
        assertThat(execution.getErrorMessage()).isNull();
    }

    @Test
    void succeedingRecordsTheArtifactAndKeepsIdentity() {
        BackupExecution started = running();

        BackupExecution done = started.succeeded("/backups/shop.sql", 4096L, FINISHED);

        assertThat(done.getId()).isEqualTo(started.getId());
        assertThat(done.getTargetId()).isEqualTo(started.getTargetId());
        assertThat(done.getStartedAt()).isEqualTo(STARTED);
        assertThat(done.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(done.getArtifactPath()).isEqualTo("/backups/shop.sql");
        assertThat(done.getSizeBytes()).isEqualTo(4096L);
        assertThat(done.getFinishedAt()).isEqualTo(FINISHED);
    }

    @Test
    void failingRecordsTheReason() {
        BackupExecution done = running().failed("mysqldump exited with 2: Access denied", FINISHED);

        assertThat(done.getStatus()).isEqualTo(BackupStatus.FAILED);
        assertThat(done.getErrorMessage()).isEqualTo("mysqldump exited with 2: Access denied");
        assertThat(done.getArtifactPath()).isNull();
    }

    @Test
    void failingWithNothingToSayStillSaysSomething() {
        assertThat(running().failed("   ", FINISHED).getErrorMessage())
                .isEqualTo("Backup failed, with no reason reported");
        assertThat(running().failed(null, FINISHED).getErrorMessage())
                .isEqualTo("Backup failed, with no reason reported");
    }

    /**
     * The reason the transitions are on the model: a result arriving late must
     * not be able to overwrite an outcome that is already recorded.
     */
    @Test
    void aFinishedExecutionCannotBeFinishedAgain() {
        BackupExecution done = running().succeeded("/backups/shop.sql", 1L, FINISHED);

        assertThatThrownBy(() -> done.succeeded("/backups/other.sql", 2L, FINISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished as SUCCEEDED");
        assertThatThrownBy(() -> done.failed("too late", FINISHED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedExecutionCannotBeRevived() {
        BackupExecution failed = running().failed("boom", FINISHED);

        assertThatThrownBy(() -> failed.succeeded("/backups/shop.sql", 1L, FINISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished as FAILED");
    }

    @Test
    void refusesAFinishedStatusWithoutAFinishTimestamp() {
        assertThatThrownBy(() -> BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(BackupStatus.SUCCEEDED).startedAt(STARTED)
                .artifactPath("/x").sizeBytes(1L)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a finish timestamp");
    }

    @Test
    void refusesARunningStatusThatHasAFinishTimestamp() {
        assertThatThrownBy(() -> BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(BackupStatus.RUNNING).startedAt(STARTED).finishedAt(FINISHED)
                .build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void truncatesAnOverlongErrorToTheColumnWidth() {
        assertThat(running().failed("x".repeat(5000), FINISHED).getErrorMessage())
                .hasSize(BackupExecution.MAX_ERROR_LENGTH);
    }

    @Test
    void durationIsMeasuredToTheFinishOrToNowWhileRunning() {
        assertThat(running().succeeded("/x", 1L, FINISHED).duration(Instant.MAX))
                .isEqualTo(Duration.ofSeconds(150));
        assertThat(running().duration(STARTED.plusSeconds(42)))
                .isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void statusKnowsWhichOfItsValuesAreTerminal() {
        assertThat(BackupStatus.RUNNING.isFinished()).isFalse();
        assertThat(BackupStatus.SUCCEEDED.isFinished()).isTrue();
        assertThat(BackupStatus.FAILED.isFinished()).isTrue();
    }
}
