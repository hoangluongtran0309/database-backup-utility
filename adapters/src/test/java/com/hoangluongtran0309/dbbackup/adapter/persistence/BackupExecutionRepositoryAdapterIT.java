package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.TestAdaptersApplication;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.BackupStatus;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
class BackupExecutionRepositoryAdapterIT {

    private static final Instant STARTED = Instant.parse("2026-09-09T10:00:00Z");

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
        assertThat(found.getStatus()).isEqualTo(BackupStatus.RUNNING);
        assertThat(found.getTargetId()).isEqualTo(targetId);
        assertThat(found.getStartedAt()).isEqualTo(STARTED);
        assertThat(found.getFinishedAt()).isNull();
    }

    @Test
    void savesAndReadsBackASucceededExecution() {
        BackupExecution running = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        executions.save(running.succeeded("/backups/shop.sql", 4096L, STARTED.plusSeconds(90)));

        BackupExecution found = executions.findById(running.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(BackupStatus.SUCCEEDED);
        assertThat(found.getArtifactPath()).isEqualTo("/backups/shop.sql");
        assertThat(found.getSizeBytes()).isEqualTo(4096L);
        assertThat(found.getFinishedAt()).isEqualTo(STARTED.plusSeconds(90));
    }

    @Test
    void savesAndReadsBackAFailedExecution() {
        BackupExecution running = executions.save(BackupExecution.started(UUID.randomUUID(), targetId, STARTED));

        executions.save(running.failed("mysqldump exited with 2", STARTED.plusSeconds(3)));

        BackupExecution found = executions.findById(running.getId()).orElseThrow();
        assertThat(found.getStatus()).isEqualTo(BackupStatus.FAILED);
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
        executions.save(done.succeeded("/backups/shop.sql", 1L, STARTED.plusSeconds(1)));

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

    private void deleteExecution(UUID id) {
        // Only this test needs to remove executions; the application cannot yet,
        // which is exactly why removing a target is refused above.
        executionJpa.deleteById(id);
        executionJpa.flush();
    }

    @Autowired
    private BackupExecutionJpaRepository executionJpa;

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
