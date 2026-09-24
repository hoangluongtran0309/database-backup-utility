package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationExecution;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationStatus;
import com.hoangluongtran0309.dbbackup.core.port.RestoreVerificationExecutionRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class RestoreVerificationExecutionRepositoryAdapter implements RestoreVerificationExecutionRepository {
    private final RestoreVerificationExecutionJpaRepository repository;

    @Override
    public RestoreVerificationExecution save(RestoreVerificationExecution execution) {
        return RestoreVerificationExecutionMapper.toDomain(
                repository.saveAndFlush(RestoreVerificationExecutionMapper.toEntity(execution)));
    }

    @Override
    public Optional<RestoreVerificationExecution> findById(UUID id) {
        return repository.findById(id).map(RestoreVerificationExecutionMapper::toDomain);
    }

    @Override
    public List<RestoreVerificationExecution> findForBackupNewestFirst(UUID backupId) {
        return repository.findByBackupExecutionIdOrderByStartedAtDescIdDesc(backupId).stream()
                .map(RestoreVerificationExecutionMapper::toDomain).toList();
    }

    @Override
    public Optional<RestoreVerificationExecution> findLatestForBackup(UUID backupId) {
        return repository.findFirstByBackupExecutionIdOrderByStartedAtDescIdDesc(backupId)
                .map(RestoreVerificationExecutionMapper::toDomain);
    }

    @Override
    public List<RestoreVerificationExecution> findRunning() {
        return repository.findByStatus(RestoreVerificationStatus.RUNNING).stream()
                .map(RestoreVerificationExecutionMapper::toDomain).toList();
    }

    @Override
    public boolean existsRunningForBackup(UUID backupId) {
        return repository.existsByBackupExecutionIdAndStatus(backupId, RestoreVerificationStatus.RUNNING);
    }
}
