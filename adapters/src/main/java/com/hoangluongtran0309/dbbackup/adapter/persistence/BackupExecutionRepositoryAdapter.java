package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.HistoryPage;

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
    public boolean existsForTarget(UUID targetId) {
        return jpaRepository.existsByTargetId(targetId);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
        // Flushed so a foreign key still holding this row surfaces here rather
        // than at some later commit.
        jpaRepository.flush();
    }

    @Override
    public HistoryPage<BackupExecution> findNewestFirst(int page, int pageSize) {
        Slice<BackupExecutionEntity> slice = jpaRepository.findAllBy(PageRequest.of(page - 1, pageSize, NEWEST_FIRST));
        return new HistoryPage<>(
                slice.getContent().stream().map(BackupExecutionMapper::toDomain).toList(),
                page,
                slice.hasNext());
    }

    @Override
    public List<BackupExecution> findLatestPerTarget() {
        return jpaRepository.findLatestPerTarget().stream().map(BackupExecutionMapper::toDomain).toList();
    }

    @Override
    public List<BackupExecution> findLatestSucceededPerTarget() {
        return jpaRepository.findLatestSucceededPerTarget().stream().map(BackupExecutionMapper::toDomain).toList();
    }

    @Override
    public List<BackupExecution> findAllById(Collection<UUID> ids) {
        return jpaRepository.findAllById(ids).stream().map(BackupExecutionMapper::toDomain).toList();
    }
}
