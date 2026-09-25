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
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationStatus;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;

@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
class RestoreVerificationExecutionRepositoryAdapterIT {
    private static final Instant NOW = Instant.parse("2026-09-24T08:00:00Z");

    @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
    @DynamicPropertySource static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired private RestoreVerificationExecutionRepository verifications;
    @Autowired private BackupExecutionRepository backups;
    @Autowired private DatabaseTargetRepository targets;
    private BackupExecution backup;

    @BeforeEach
    void setUp() {
        targets.findAll().forEach(target -> {
            backups.findAllForTarget(target.getId()).forEach(value -> backups.deleteById(value.getId()));
            targets.deleteById(target.getId());
        });
        DatabaseTarget target = targets.save(DatabaseTarget.builder().id(UUID.randomUUID()).name("production")
                .engine(DatabaseEngine.MYSQL).host("db").port(3306).databaseName("shop")
                .username("backup").passwordCiphertext("sealed").createdAt(NOW)
                .verifyAfterBackup(true).build());
        backup = backups.save(BackupExecution.started(UUID.randomUUID(), target.getId(), NOW));
        assertThat(targets.findById(target.getId())).get()
                .extracting(DatabaseTarget::isVerifyAfterBackup).isEqualTo(true);
    }

    @Test
    void storesHistoryNewestFirstAndIndependentResults() {
        RestoreVerificationExecution first = verifications.save(
                RestoreVerificationExecution.started(UUID.randomUUID(), backup.getId(), NOW));
        verifications.save(first.failed("broken", NOW.plusSeconds(1)));
        RestoreVerificationExecution second = verifications.save(
                RestoreVerificationExecution.started(UUID.randomUUID(), backup.getId(), NOW.plusSeconds(2)));
        verifications.save(second.succeeded(new RestoreVerificationResult(4, "healthy"), NOW.plusSeconds(3)));

        assertThat(verifications.findForBackupNewestFirst(backup.getId()))
                .extracting(RestoreVerificationExecution::getStatus)
                .containsExactly(RestoreVerificationStatus.SUCCEEDED, RestoreVerificationStatus.FAILED);
    }

    @Test
    void databaseAllowsOnlyOneRunningAttemptPerBackup() {
        verifications.save(RestoreVerificationExecution.started(UUID.randomUUID(), backup.getId(), NOW));

        assertThatThrownBy(() -> verifications.save(
                RestoreVerificationExecution.started(UUID.randomUUID(), backup.getId(), NOW.plusSeconds(1))))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingBackupCascadesVerificationHistory() {
        RestoreVerificationExecution running = verifications.save(
                RestoreVerificationExecution.started(UUID.randomUUID(), backup.getId(), NOW));
        verifications.save(running.failed("done", NOW.plusSeconds(1)));

        backups.deleteById(backup.getId());

        assertThat(verifications.findForBackupNewestFirst(backup.getId())).isEmpty();
    }
}
