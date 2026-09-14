package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.TestAdaptersApplication;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
class RestoreExecutionRepositoryAdapterIT {

    private static final Instant STARTED = Instant.parse("2026-09-09T12:00:00Z");
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RestoreExecutionRepository restores;
    @Autowired private BackupExecutionRepository backups;
    @Autowired private DatabaseTargetRepository targets;
    @Autowired private RestoreExecutionJpaRepository restoreJpa;
    @Autowired private BackupExecutionJpaRepository backupJpa;

    private UUID targetId;
    private UUID backupId;

    @BeforeEach
    void reset() {
        restoreJpa.deleteAll();
        restoreJpa.flush();
        backupJpa.deleteAll();
        backupJpa.flush();
        targets.findAll().forEach(t -> targets.deleteById(t.getId()));

        targetId = targets.save(target("production")).getId();
        BackupExecution backup = backups.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));
        backupId = backups.save(backup.succeeded("/backups/shop.sql.gz", 1024L, SHA256, STARTED.plusSeconds(60))).getId();
    }

    @Test
    void savesAndReadsBackARunningRestore() {
        RestoreExecution saved = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));

        RestoreExecution found = restores.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(found.getBackupExecutionId()).isEqualTo(backupId);
        assertThat(found.getTargetId()).isEqualTo(targetId);
        assertThat(found.getFinishedAt()).isNull();
    }

    /** ADR-014: where the data went is stored, not derived from the backup. */
    @Test
    void recordsARestoreIntoATargetOtherThanTheBackupsOwn() {
        UUID drillId = targets.save(target("drill")).getId();

        RestoreExecution saved = restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, drillId, STARTED));

        assertThat(restores.findById(saved.getId()).orElseThrow().getTargetId()).isEqualTo(drillId);
    }

    /** Removing a target that restores went into takes those records, and only those. */
    @Test
    void removesTheRestoresIntoOneTargetAndNoOthers() {
        UUID drillId = targets.save(target("drill")).getId();
        RestoreExecution intoDrill = restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, drillId, STARTED));
        RestoreExecution intoProduction = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));

        restores.deleteForTarget(drillId);

        assertThat(restores.findById(intoDrill.getId())).isEmpty();
        assertThat(restores.findById(intoProduction.getId())).isPresent();
        targets.deleteById(drillId);
        assertThat(targets.findById(drillId)).isEmpty();
    }

    /**
     * V6's key is RESTRICT, like the others: the schema never removes history
     * by itself, the use case does after asking (ADR-008).
     */
    @Test
    void theSchemaRefusesToOrphanARestoreByRemovingItsTarget() {
        UUID drillId = targets.save(target("drill")).getId();
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, drillId, STARTED));

        assertThatThrownBy(() -> targets.deleteById(drillId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(targets.findById(drillId)).isPresent();
    }

    @Test
    void savesAndReadsBackAFailedRestore() {
        RestoreExecution running = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));

        restores.save(running.failed("mysql exited with 1: Access denied", STARTED.plusSeconds(5)));

        RestoreExecution found = restores.findById(running.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(found.getErrorMessage()).isEqualTo("mysql exited with 1: Access denied");
        assertThat(found.getFinishedAt()).isEqualTo(STARTED.plusSeconds(5));
    }

    @Test
    void listsNewestFirst() {
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED.plusSeconds(60)));

        assertThat(restores.findNewestFirst(1, 50).items())
                .extracting(RestoreExecution::getStartedAt)
                .containsExactly(STARTED.plusSeconds(60), STARTED);
    }

    @Test
    void pagesTheHistoryNewestFirst() {
        for (int i = 0; i < 3; i++) {
            restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED.plusSeconds(i)));
        }

        assertThat(restores.findNewestFirst(1, 2).items()).extracting(RestoreExecution::getStartedAt)
                .containsExactly(STARTED.plusSeconds(2), STARTED.plusSeconds(1));
        assertThat(restores.findNewestFirst(1, 2).hasOlder()).isTrue();
        assertThat(restores.findNewestFirst(2, 2).items()).extracting(RestoreExecution::getStartedAt)
                .containsExactly(STARTED);
        assertThat(restores.findNewestFirst(2, 2).hasOlder()).isFalse();
    }

    @Test
    void findsOnlyTheRestoresStillRunning() {
        RestoreExecution running = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));
        RestoreExecution done = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));
        restores.save(done.succeeded(STARTED.plusSeconds(30)));

        assertThat(restores.findRunning())
                .extracting(RestoreExecution::getId)
                .containsExactly(running.getId());
    }

    /**
     * Removing a backup must not silently erase the record that it was once
     * restored somewhere.
     */
    @Test
    void refusesToRemoveABackupThatHasBeenRestored() {
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));

        assertThatThrownBy(() -> {
            backupJpa.deleteById(backupId);
            backupJpa.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void countsAndRemovesTheRestoresBelongingToOneBackup() {
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED.plusSeconds(60)));
        assertThat(restores.countForBackup(backupId)).isEqualTo(2);

        restores.deleteForBackup(backupId);

        assertThat(restores.countForBackup(backupId)).isZero();
        assertThat(restores.findNewestFirst(1, 50).isEmpty()).isTrue();
    }

    /** Which is what makes the backup itself deletable afterwards. */
    @Test
    void removingTheRestoresReleasesTheForeignKeyOnTheBackup() {
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, targetId, STARTED));

        restores.deleteForBackup(backupId);
        backups.deleteById(backupId);

        assertThat(backups.findById(backupId)).isEmpty();
    }

    @Test
    void deletingRestoresForABackupThatHasNoneIsHarmless() {
        restores.deleteForBackup(UUID.randomUUID());

        assertThat(restores.countForBackup(backupId)).isZero();
    }

    private static DatabaseTarget target(String name) {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID()).name(name).host("127.0.0.1").port(3306)
                .databaseName("shop").username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==").createdAt(STARTED)
                .build();
    }
}
