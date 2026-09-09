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

    private UUID backupId;

    @BeforeEach
    void reset() {
        restoreJpa.deleteAll();
        restoreJpa.flush();
        backupJpa.deleteAll();
        backupJpa.flush();
        targets.findAll().forEach(t -> targets.deleteById(t.getId()));

        UUID targetId = targets.save(target()).getId();
        BackupExecution backup = backups.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));
        backupId = backups.save(backup.succeeded("/backups/shop.sql.gz", 1024L, STARTED.plusSeconds(60))).getId();
    }

    @Test
    void savesAndReadsBackARunningRestore() {
        RestoreExecution saved = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, STARTED));

        RestoreExecution found = restores.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(found.getBackupExecutionId()).isEqualTo(backupId);
        assertThat(found.getFinishedAt()).isNull();
    }

    @Test
    void savesAndReadsBackAFailedRestore() {
        RestoreExecution running = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, STARTED));

        restores.save(running.failed("mysql exited with 1: Access denied", STARTED.plusSeconds(5)));

        RestoreExecution found = restores.findById(running.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(found.getErrorMessage()).isEqualTo("mysql exited with 1: Access denied");
        assertThat(found.getFinishedAt()).isEqualTo(STARTED.plusSeconds(5));
    }

    @Test
    void listsNewestFirst() {
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, STARTED));
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, STARTED.plusSeconds(60)));

        assertThat(restores.findAllNewestFirst())
                .extracting(RestoreExecution::getStartedAt)
                .containsExactly(STARTED.plusSeconds(60), STARTED);
    }

    @Test
    void findsOnlyTheRestoresStillRunning() {
        RestoreExecution running = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, STARTED));
        RestoreExecution done = restores.save(
                RestoreExecution.started(UUID.randomUUID(), backupId, STARTED));
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
        restores.save(RestoreExecution.started(UUID.randomUUID(), backupId, STARTED));

        assertThatThrownBy(() -> {
            backupJpa.deleteById(backupId);
            backupJpa.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    private static DatabaseTarget target() {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID()).name("production").host("127.0.0.1").port(3306)
                .databaseName("shop").username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==").createdAt(STARTED)
                .build();
    }
}
