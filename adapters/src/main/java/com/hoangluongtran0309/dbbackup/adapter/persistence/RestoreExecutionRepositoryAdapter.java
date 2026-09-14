package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.model.ExecutionStatus;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.HistoryPage;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class RestoreExecutionRepositoryAdapter implements RestoreExecutionRepository {

    private static final Sort NEWEST_FIRST =
            Sort.by(Sort.Order.desc("startedAt"), Sort.Order.desc("id"));

    private final RestoreExecutionJpaRepository jpaRepository;

    @Override
    public RestoreExecution save(RestoreExecution execution) {
        // Flushed so the RUNNING row is visible to the thread about to update it.
        return toDomain(jpaRepository.saveAndFlush(toEntity(execution)));
    }

    @Override
    public Optional<RestoreExecution> findById(UUID id) {
        return jpaRepository.findById(id).map(RestoreExecutionRepositoryAdapter::toDomain);
    }

    @Override
    public HistoryPage<RestoreExecution> findNewestFirst(int page, int pageSize) {
        Slice<RestoreExecutionEntity> slice = jpaRepository.findAllBy(PageRequest.of(page - 1, pageSize, NEWEST_FIRST));
        return new HistoryPage<>(
                slice.getContent().stream().map(RestoreExecutionRepositoryAdapter::toDomain).toList(),
                page,
                slice.hasNext());
    }

    @Override
    public List<RestoreExecution> findRunning() {
        return jpaRepository.findByStatus(ExecutionStatus.RUNNING).stream()
                .map(RestoreExecutionRepositoryAdapter::toDomain)
                .toList();
    }

    @Override
    public long countForBackup(UUID backupExecutionId) {
        return jpaRepository.countByBackupExecutionId(backupExecutionId);
    }

    @Override
    public long countForBackups(Collection<UUID> backupExecutionIds) {
        // An empty IN list is not something to trust every dialect with.
        return backupExecutionIds.isEmpty() ? 0 : jpaRepository.countByBackupExecutionIdIn(backupExecutionIds);
    }

    @Override
    public long countInvolvingTarget(UUID targetId) {
        return jpaRepository.countInvolvingTarget(targetId);
    }

    @Override
    @Transactional
    public void deleteForBackup(UUID backupExecutionId) {
        jpaRepository.deleteByBackupExecutionId(backupExecutionId);
        jpaRepository.flush();
    }

    @Override
    @Transactional
    public void deleteForTarget(UUID targetId) {
        jpaRepository.deleteByTargetId(targetId);
        jpaRepository.flush();
    }

    private static RestoreExecutionEntity toEntity(RestoreExecution execution) {
        RestoreExecutionEntity entity = new RestoreExecutionEntity();
        entity.setId(execution.getId());
        entity.setBackupExecutionId(execution.getBackupExecutionId());
        entity.setTargetId(execution.getTargetId());
        entity.setStatus(execution.getStatus());
        entity.setStartedAt(execution.getStartedAt());
        entity.setFinishedAt(execution.getFinishedAt());
        entity.setErrorMessage(execution.getErrorMessage());
        return entity;
    }

    private static RestoreExecution toDomain(RestoreExecutionEntity entity) {
        return RestoreExecution.builder()
                .id(entity.getId())
                .backupExecutionId(entity.getBackupExecutionId())
                .targetId(entity.getTargetId())
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
