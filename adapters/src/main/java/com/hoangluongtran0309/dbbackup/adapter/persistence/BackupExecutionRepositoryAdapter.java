package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class BackupExecutionRepositoryAdapter implements BackupExecutionRepository {

    /** Ties broken by id so the order is stable when two backups start in the same millisecond. */
    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    private final BackupExecutionJpaRepository jpaRepository;

    @Override
    public BackupExecution save(BackupExecution execution) {
        // Flushed here so that the RUNNING row is visible to the background
        // thread that is about to update it.
        return BackupExecutionMapper.toDomain(
                jpaRepository.saveAndFlush(BackupExecutionMapper.toEntity(execution)));
    }

    @Override
    public Optional<BackupExecution> findById(UUID id) {
        return jpaRepository.findById(id).map(BackupExecutionMapper::toDomain);
    }

    @Override
    public List<BackupExecution> findRunning() {
        return jpaRepository.findByStatus(ExecutionStatus.RUNNING).stream()
                .map(BackupExecutionMapper::toDomain)
                .toList();
    }

    @Override
    public List<BackupExecution> findAllNewestFirst() {
        return jpaRepository.findAll(NEWEST_FIRST).stream()
                .map(BackupExecutionMapper::toDomain)
                .toList();
    }
}
