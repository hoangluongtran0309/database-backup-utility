package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.TestAdaptersApplication;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
class BackupExecutionRepositoryAdapterIT {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");
    private static final String SHA256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private BackupExecutionRepository executions;

    @Autowired
    private DatabaseTargetRepository targets;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID targetId;

    @BeforeEach
    void reset() {
        executions.findAllNewestFirst().forEach(e -> deleteExecution(e.getId()));
        targets.findAll().forEach(t -> targets.deleteById(t.getId()));
        targetId = targets.save(target("production")).getId();
    }

    @Test
    void savesAndReadsBackARunningExecution() {
        BackupExecution saved = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        BackupExecution found = executions.findById(saved.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(found.getTargetId()).isEqualTo(targetId);
        assertThat(found.getStartedAt()).isEqualTo(STARTED);
        assertThat(found.getFinishedAt()).isNull();
    }

    @Test
    void savesAndReadsBackASucceededExecution() {
        BackupExecution running = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        executions.save(running.succeeded("/backups/shop.sql", 4096L, SHA256, STARTED.plusSeconds(90)));

        BackupExecution found = executions.findById(running.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.SUCCEEDED);
        assertThat(found.getArtifactPath()).isEqualTo("/backups/shop.sql");
        assertThat(found.getSizeBytes()).isEqualTo(4096L);
        assertThat(found.getSha256()).isEqualTo(SHA256);
        assertThat(found.getFinishedAt()).isEqualTo(STARTED.plusSeconds(90));
    }

    /**
     * V5's check, not only the model's: a row written by hand or by an older
     * build must not be able to carry a checksum the console would trust.
     */
    @Test
    void theSchemaRefusesAChecksumOnAnythingButASuccess() {
        BackupExecution running = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        assertThatThrownBy(() -> jdbc.update(
                "update backup_executions set sha256 = ? where id = ?", SHA256, running.getId()))
                .hasMessageContaining("ck_backup_executions_sha256");
    }

    @Test
    void savesAndReadsBackAFailedExecution() {
        BackupExecution running = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        executions.save(running.failed("mysqldump exited with 2", STARTED.plusSeconds(3)));

        BackupExecution found = executions.findById(running.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(found.getErrorMessage()).isEqualTo("mysqldump exited with 2");
        assertThat(found.getArtifactPath()).isNull();
    }

    @Test
    void listsNewestFirst() {
        executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));
        executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED.plusSeconds(60)));
        executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED.minusSeconds(60)));

        assertThat(executions.findAllNewestFirst())
                .extracting(BackupExecution::getStartedAt)
                .containsExactly(STARTED.plusSeconds(60), STARTED, STARTED.minusSeconds(60));
    }

    @Test
    void findsOnlyTheExecutionsStillRunning() {
        BackupExecution running = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));
        BackupExecution done = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));
        executions.save(done.succeeded("/backups/shop.sql", 1L, SHA256, STARTED.plusSeconds(1)));

        assertThat(executions.findRunning())
                .extracting(BackupExecution::getId)
                .containsExactly(running.getId());
    }

    /**
     * The backups are the valuable thing. Removing a target must not be a way
     * to lose them by accident, nor to strand their files on disk with nothing
     * in the database pointing at them.
     */
    @Test
    void refusesToRemoveATargetThatStillHasBackups() {
        executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        assertThatThrownBy(() -> targets.deleteById(targetId))
                .isInstanceOf(TargetInUseException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("still has backups");

        assertThat(targets.findById(targetId)).isPresent();
    }

    @Test
    void removesATargetThatHasNoBackups() {
        targets.deleteById(targetId);

        assertThat(targets.findById(targetId)).isEmpty();
    }

    /**
     * The chain the console walks an operator through: delete the backups, and
     * only then can the target go.
     */
    @Test
    void deletingTheBackupsIsWhatMakesTheTargetRemovable() {
        BackupExecution backup = executions.save(
                BackupExecution.started(UUID.randomUUID(), targetId, STARTED));
        assertThatThrownBy(() -> targets.deleteById(targetId))
                .isInstanceOf(TargetInUseException.class);

        executions.deleteById(backup.getId());
        targets.deleteById(targetId);

        assertThat(targets.findById(targetId)).isEmpty();
    }

    @Test
    void deletingAnUnknownExecutionIsNotAnError() {
        executions.deleteById(UUID.randomUUID());
    }

    private void deleteExecution(UUID id) {
        executions.deleteById(id);
    }

    private static DatabaseTarget target(String name) {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name(name)
                .host("127.0.0.1")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("Y2lwaGVydGV4dA==")
                .createdAt(STARTED)
                .build();
    }
}
