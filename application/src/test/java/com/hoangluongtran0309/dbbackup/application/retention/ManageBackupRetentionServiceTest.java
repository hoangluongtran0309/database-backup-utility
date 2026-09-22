package com.hoangluongtran0309.dbbackup.application.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupRetentionPolicyRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

@ExtendWith(MockitoExtension.class)
class ManageBackupRetentionServiceTest {

    private static final UUID TARGET_ID = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-09-22T04:00:00Z");

    @Mock private BackupRetentionPolicyRepository policies;
    @Mock private DatabaseTargetRepository targets;

    private ManageBackupRetentionService service;

    @BeforeEach
    void setUp() {
        service = new ManageBackupRetentionService(
                policies, targets, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void listsTargetsWithAndWithoutPolicies() {
        DatabaseTarget first = target(TARGET_ID, "alpha");
        DatabaseTarget second = target(UUID.randomUUID(), "beta");
        BackupRetentionPolicy policy = BackupRetentionPolicy.create(TARGET_ID, 7, NOW);
        when(targets.findAll()).thenReturn(List.of(first, second));
        when(policies.findAll()).thenReturn(List.of(policy));

        var views = service.listAll();

        assertThat(views).hasSize(2);
        assertThat(views.get(0).policy()).contains(policy);
        assertThat(views.get(1).policy()).isEmpty();
    }

    @Test
    void createsAPolicyForAnExistingTarget() {
        when(targets.findById(TARGET_ID)).thenReturn(Optional.of(target(TARGET_ID, "production")));
        when(policies.findByTargetId(TARGET_ID)).thenReturn(Optional.empty());
        when(policies.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

        BackupRetentionPolicy saved = service.save(TARGET_ID, new SaveBackupRetentionPolicyCommand(5));

        assertThat(saved.getKeepSuccessful()).isEqualTo(5);
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
        assertThat(saved.getLastRunAt()).isNull();
    }

    @Test
    void editingResetsThePreviousOutcome() {
        BackupRetentionPolicy current = BackupRetentionPolicy.create(
                TARGET_ID, 5, NOW.minusSeconds(120)).completed(2, null, NOW.minusSeconds(60));
        when(targets.findById(TARGET_ID)).thenReturn(Optional.of(target(TARGET_ID, "production")));
        when(policies.findByTargetId(TARGET_ID)).thenReturn(Optional.of(current));
        when(policies.save(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));

        BackupRetentionPolicy saved = service.save(TARGET_ID, new SaveBackupRetentionPolicyCommand(10));

        assertThat(saved.getKeepSuccessful()).isEqualTo(10);
        assertThat(saved.getLastRunAt()).isNull();
        assertThat(saved.getCreatedAt()).isEqualTo(current.getCreatedAt());
    }

    @Test
    void refusesAPolicyForAnUnknownTarget() {
        when(targets.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.save(TARGET_ID, new SaveBackupRetentionPolicyCommand(5)))
                .isInstanceOf(java.util.NoSuchElementException.class);
    }

    @Test
    void disablingOnlyDeletesThePolicy() {
        service.disable(TARGET_ID);

        verify(policies).deleteByTargetId(TARGET_ID);
    }

    private static DatabaseTarget target(UUID id, String name) {
        return DatabaseTarget.builder()
                .id(id)
                .name(name)
                .engine(DatabaseEngine.MYSQL)
                .host("db.internal")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("sealed")
                .createdAt(NOW)
                .build();
    }
}
