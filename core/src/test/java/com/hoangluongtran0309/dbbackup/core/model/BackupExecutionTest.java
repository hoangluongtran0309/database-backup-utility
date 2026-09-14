package com.hoangluongtran0309.dbbackup.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BackupExecutionTest {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
    private static final Instant FINISHED = Instant.parse("2026-09-09T10:02:30Z");

    private static BackupExecution running() {
        return BackupExecution.started(UUID.randomUUID(), UUID.randomUUID(), STARTED);
    }

    @Test
    void startsRunningWithNothingRecordedYet() {
        BackupExecution execution = running();

        assertThat(execution.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(execution.getFinishedAt()).isNull();
        assertThat(execution.getArtifactPath()).isNull();
        assertThat(execution.getSizeBytes()).isNull();
        assertThat(execution.getErrorMessage()).isNull();
    }

    @Test
    void succeedingRecordsTheArtifactAndKeepsIdentity() {
        BackupExecution started = running();

        BackupExecution done = started.succeeded("/backups/shop.sql", 4096L, SHA256, FINISHED);

        assertThat(done.getId()).isEqualTo(started.getId());
        assertThat(done.getTargetId()).isEqualTo(started.getTargetId());
        assertThat(done.getStartedAt()).isEqualTo(STARTED);
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(done.getArtifactPath()).isEqualTo("/backups/shop.sql");
        assertThat(done.getSizeBytes()).isEqualTo(4096L);
        assertThat(done.getSha256()).isEqualTo(SHA256);
        assertThat(done.hasChecksum()).isTrue();
        assertThat(done.getFinishedAt()).isEqualTo(FINISHED);
    }

    @Test
    void aBackupCannotSucceedWithoutItsChecksum() {
        assertThatThrownBy(() -> running().succeeded("/backups/shop.sql", 1L, null, FINISHED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("checksum");
    }

    /** The form sha256sum prints, so the console and the shell can be compared by eye. */
    @ParameterizedTest
    @ValueSource(strings = {
            "9F86D081884C7D659A2FEAA0C55AD015A3BF4F1B2B0B822CD15D6C15B0F00A08",
            "9f86d081",
            "not-a-checksum-at-all-but-exactly-sixty-four-characters-long-ok"})
    void refusesAChecksumThatIsNotLowerCaseHexOfTheRightLength(String bad) {
        assertThatThrownBy(() -> running().succeeded("/backups/shop.sql", 1L, bad, FINISHED))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void onlyASuccessfulBackupCarriesAChecksum() {
        assertThatThrownBy(() -> BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(ExecutionStatus.FAILED).startedAt(STARTED).finishedAt(FINISHED)
                .sha256(SHA256).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Backups made before checksums were recorded read back with none. */
    @Test
    void aSuccessfulBackupFromBeforeChecksumsHasNone() {
        BackupExecution legacy = BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(ExecutionStatus.SUCCEEDED).startedAt(STARTED).finishedAt(FINISHED)
                .artifactPath("/backups/shop.sql.gz").sizeBytes(1L).build();

        assertThat(legacy.hasChecksum()).isFalse();
    }

    @Test
    void failingRecordsTheReason() {
        BackupExecution done = running().failed("mysqldump exited with 2: Access denied", FINISHED);

        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.FAILED);
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
        BackupExecution done = running().succeeded("/backups/shop.sql", 1L, SHA256, FINISHED);

        assertThatThrownBy(() -> done.succeeded("/backups/other.sql", 2L, SHA256, FINISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished as SUCCEEDED");
        assertThatThrownBy(() -> done.failed("too late", FINISHED))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aFailedExecutionCannotBeRevived() {
        BackupExecution failed = running().failed("boom", FINISHED);

        assertThatThrownBy(() -> failed.succeeded("/backups/shop.sql", 1L, SHA256, FINISHED))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already finished as FAILED");
    }

    @Test
    void refusesAFinishedStatusWithoutAFinishTimestamp() {
        assertThatThrownBy(() -> BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(ExecutionStatus.SUCCEEDED).startedAt(STARTED)
                .artifactPath("/x").sizeBytes(1L)
                .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("needs a finish timestamp");
    }

    @Test
    void refusesARunningStatusThatHasAFinishTimestamp() {
        assertThatThrownBy(() -> BackupExecution.builder()
                .id(UUID.randomUUID()).targetId(UUID.randomUUID())
                .status(ExecutionStatus.RUNNING).startedAt(STARTED).finishedAt(FINISHED)
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
        assertThat(running().succeeded("/x", 1L, SHA256, FINISHED).duration(Instant.MAX))
                .isEqualTo(Duration.ofSeconds(150));
        assertThat(running().duration(STARTED.plusSeconds(42)))
                .isEqualTo(Duration.ofSeconds(42));
    }

    @Test
    void statusKnowsWhichOfItsValuesAreTerminal() {
        assertThat(ExecutionStatus.RUNNING.isFinished()).isFalse();
        assertThat(ExecutionStatus.SUCCEEDED.isFinished()).isTrue();
        assertThat(ExecutionStatus.FAILED.isFinished()).isTrue();
    }
}
