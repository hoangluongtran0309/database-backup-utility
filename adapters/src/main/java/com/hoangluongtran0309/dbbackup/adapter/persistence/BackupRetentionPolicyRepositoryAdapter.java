package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.model.BackupRetentionPolicy;
import com.hoangluongtran0309.dbbackup.core.port.BackupRetentionPolicyRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class BackupRetentionPolicyRepositoryAdapter implements BackupRetentionPolicyRepository {

    private final BackupRetentionPolicyJpaRepository jpaRepository;

    @Override
    public BackupRetentionPolicy save(BackupRetentionPolicy policy) {
        return BackupRetentionPolicyMapper.toDomain(
                jpaRepository.saveAndFlush(BackupRetentionPolicyMapper.toEntity(policy)));
    }

    @Override
    public Optional<BackupRetentionPolicy> findByTargetId(UUID targetId) {
        return jpaRepository.findById(targetId).map(BackupRetentionPolicyMapper::toDomain);
    }

    @Override
    public List<BackupRetentionPolicy> findAll() {
        return jpaRepository.findAll(Sort.by("targetId")).stream()
                .map(BackupRetentionPolicyMapper::toDomain)
                .toList();
    }

    @Override
    public void deleteByTargetId(UUID targetId) {
        jpaRepository.findById(targetId).ifPresent(policy -> {
            jpaRepository.delete(policy);
            jpaRepository.flush();
        });
    }

    @Override
    @Transactional
    public boolean recordRunResult(
            UUID targetId,
            Instant expectedUpdatedAt,
            Instant ranAt,
            int deletedCount,
            String error) {

        return jpaRepository.recordRunResult(
                targetId, expectedUpdatedAt, ranAt, deletedCount, error) == 1;
    }
}
