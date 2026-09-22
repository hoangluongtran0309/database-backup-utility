package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.exception.DuplicateStorageProfileNameException;
import com.hoangluongtran0309.dbbackup.core.exception.StorageProfileInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;
import com.hoangluongtran0309.dbbackup.core.port.StorageProfileRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class StorageProfileRepositoryAdapter implements StorageProfileRepository {
    private final StorageProfileJpaRepository repository;

    @Override public StorageProfile save(StorageProfile profile) {
        if (repository.existsByNameOtherThan(profile.getName(), profile.getId())) {
            throw new DuplicateStorageProfileNameException(profile.getName());
        }
        try {
            return StorageProfileMapper.toDomain(repository.saveAndFlush(StorageProfileMapper.toEntity(profile)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateStorageProfileNameException(profile.getName());
        }
    }
    @Override public Optional<StorageProfile> findById(UUID id) {
        return repository.findById(id).map(StorageProfileMapper::toDomain);
    }
    @Override public List<StorageProfile> findAll() {
        return repository.findAll(Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"))).stream()
                .map(StorageProfileMapper::toDomain).toList();
    }
    @Override @Transactional public void recordConnectionCheck(UUID id, ConnectionCheck check) {
        repository.updateConnectionCheck(id, check.successful(), check.message(), check.checkedAt());
    }
    @Override public void deleteById(UUID id) {
        String name = repository.findById(id).map(StorageProfileEntity::getName).orElse(null);
        try {
            repository.deleteById(id);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new StorageProfileInUseException(name == null ? id.toString() : name);
        }
    }
}
