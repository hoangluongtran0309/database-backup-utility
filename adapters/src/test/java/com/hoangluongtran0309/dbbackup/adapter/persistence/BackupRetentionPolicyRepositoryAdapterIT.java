package com.hoangluongtran0309.dbbackup.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.hoangluongtran0309.dbbackup.adapter.TestAdaptersApplication;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.BackupRetentionPolicyRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

@SpringBootTest(classes = TestAdaptersApplication.class)
@Testcontainers
class BackupRetentionPolicyRepositoryAdapterIT {

    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("dbbackup.mariadb.client-path", () -> "/bin/true");
        registry.add("dbbackup.mariadb.dump-path", () -> "/bin/true");
        registry.add("dbbackup.sqlite.client-path", () -> "/bin/true");
    }

    @Autowired private BackupRetentionPolicyRepository policies;
    @Autowired private BackupExecutionRepository backups;
    @Autowired private RestoreExecutionRepository restores;
    @Autowired private DatabaseTargetRepository targets;

    @Test
    void roundTripsAndConditionallyRecordsTheLatestOutcome() {
        DatabaseTarget target = targets.save(target("retention-roundtrip-" + UUID.randomUUID()));
        BackupRetentionPolicy saved = policies.save(BackupRetentionPolicy.create(target.getId(), 7, NOW));

        assertThat(policies.findByTargetId(target.getId())).get()
                .extracting(BackupRetentionPolicy::getKeepSuccessful)
                .isEqualTo(saved.getKeepSuccessful());
        assertThat(policies.recordRunResult(
                target.getId(), NOW, NOW.plusSeconds(60), 3, "permission denied")).isTrue();

        BackupRetentionPolicy completed = policies.findByTargetId(target.getId()).orElseThrow();
        assertThat(completed.getLastDeletedCount()).isEqualTo(3);
        assertThat(completed.getLastError()).isEqualTo("permission denied");
        assertThat(policies.recordRunResult(
                target.getId(), NOW.minusSeconds(1), NOW.plusSeconds(120), 9, null)).isFalse();
        assertThat(policies.findByTargetId(target.getId()).orElseThrow().getLastDeletedCount()).isEqualTo(3);
    }

    @Test
    void retentionCandidatesKeepNewestUnprotectedAndIgnoreProtectedBackups() {
        DatabaseTarget target = targets.save(target("retention-candidates-" + UUID.randomUUID()));
        BackupExecution oldest = backups.save(success(target.getId(), NOW.minusSeconds(500)));
        BackupExecution protectedBackup = backups.save(success(target.getId(), NOW.minusSeconds(400)));
        BackupExecution middle = backups.save(success(target.getId(), NOW.minusSeconds(300)));
        BackupExecution newest = backups.save(success(target.getId(), NOW.minusSeconds(200)));
        backups.save(BackupExecution.started(UUID.randomUUID(), target.getId(), NOW.minusSeconds(100))
                .failed("client failed", NOW.minusSeconds(90)));
        restores.save(RestoreExecution.started(
                UUID.randomUUID(), protectedBackup.getId(), target.getId(), NOW.minusSeconds(50))
                .failed("drill failed", NOW.minusSeconds(40)));

        List<BackupExecution> candidates = backups.findRetentionCandidates(target.getId(), 2);

        assertThat(candidates).extracting(BackupExecution::getId).containsExactly(oldest.getId());
        assertThat(candidates).extracting(BackupExecution::getId)
                .doesNotContain(protectedBackup.getId(), middle.getId(), newest.getId());
    }

    @Test
    void deletingATargetCascadesOnlyItsPolicyMetadata() {
        DatabaseTarget target = targets.save(target("retention-cascade-" + UUID.randomUUID()));
        policies.save(BackupRetentionPolicy.create(target.getId(), 2, NOW));

        targets.deleteById(target.getId());

        assertThat(policies.findByTargetId(target.getId())).isEmpty();
    }

    private static BackupExecution success(UUID targetId, Instant startedAt) {
        return BackupExecution.started(UUID.randomUUID(), targetId, startedAt)
                .succeeded(
                        "/backups/" + UUID.randomUUID() + ".dump",
                        100,
                        "a".repeat(64),
                        startedAt.plusSeconds(30));
    }

    private static DatabaseTarget target(String name) {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name(name)
                .engine(DatabaseEngine.MYSQL)
                .host("127.0.0.1")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("sealed")
                .createdAt(NOW)
                .build();
    }
}
