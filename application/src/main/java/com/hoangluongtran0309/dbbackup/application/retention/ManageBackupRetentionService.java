package com.hoangluongtran0309.dbbackup.application.retention;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupRetentionPolicyRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

import lombok.RequiredArgsConstructor;

/** Enables, changes and disables per-target automatic retention. */
@Service
@RequiredArgsConstructor
public class ManageBackupRetentionService {

    public record RetentionView(DatabaseTarget target, Optional<BackupRetentionPolicy> policy) {
    }

    private final BackupRetentionPolicyRepository policies;
    private final DatabaseTargetRepository targets;
    private final Clock clock;

    public List<RetentionView> listAll() {
        Map<UUID, BackupRetentionPolicy> byTarget = policies.findAll().stream()
                .collect(Collectors.toMap(BackupRetentionPolicy::getTargetId, Function.identity()));
        return targets.findAll().stream()
                .map(target -> new RetentionView(target, Optional.ofNullable(byTarget.get(target.getId()))))
                .toList();
    }

    public DatabaseTarget getTarget(UUID targetId) {
        return targets.findById(targetId)
                .orElseThrow(() -> new NoSuchElementException("No database target with id " + targetId));
    }

    public Optional<BackupRetentionPolicy> findForTarget(UUID targetId) {
        getTarget(targetId);
        return policies.findByTargetId(targetId);
    }

    public BackupRetentionPolicy save(UUID targetId, SaveBackupRetentionPolicyCommand command) {
        getTarget(targetId);
        Instant now = clock.instant();
        BackupRetentionPolicy policy = policies.findByTargetId(targetId)
                .map(current -> current.edited(command.keepSuccessful(), now))
                .orElseGet(() -> BackupRetentionPolicy.create(targetId, command.keepSuccessful(), now));
        return policies.save(policy);
    }

    /** Disabling retention never touches a backup. */
    public void disable(UUID targetId) {
        policies.deleteByTargetId(targetId);
    }
}
