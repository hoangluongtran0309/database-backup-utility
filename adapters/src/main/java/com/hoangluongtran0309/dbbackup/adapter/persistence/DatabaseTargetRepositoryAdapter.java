package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class DatabaseTargetRepositoryAdapter implements DatabaseTargetRepository {

    /** Name of the unique index in V1, used to tell duplicates from other constraint failures. */
    private static final String NAME_INDEX = "ux_database_targets_name";

    /**
     * Ties are broken by id so that two targets whose names differ only by case
     * or surrounding space still come back in a stable order across calls.
     */
    private static final Sort BY_NAME = Sort.by(
            Sort.Order.asc("name").ignoreCase(),
            Sort.Order.asc("id"));

    private final DatabaseTargetJpaRepository jpaRepository;

    @Override
    public DatabaseTarget save(DatabaseTarget target) {
        try {
            // saveAndFlush, not save: without the flush the constraint violation
            // would surface later at transaction commit, far from this catch.
            return DatabaseTargetMapper.toDomain(
                    jpaRepository.saveAndFlush(DatabaseTargetMapper.toEntity(target)));
        } catch (DataIntegrityViolationException e) {
            if (violatesNameIndex(e)) {
                throw new DuplicateTargetNameException(target.getName());
            }
            throw e;
        }
    }

    @Override
    public List<DatabaseTarget> findAll() {
        return jpaRepository.findAll(BY_NAME).stream()
                .map(DatabaseTargetMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<DatabaseTarget> findById(UUID id) {
        return jpaRepository.findById(id).map(DatabaseTargetMapper::toDomain);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
    }

    @Override
    @Transactional
    public void recordConnectionCheck(UUID id, ConnectionCheck check) {
        // A probe against a target deleted in another tab updates no rows.
        // That is not an error worth failing the request over.
        jpaRepository.updateConnectionCheck(
                id, check.successful(), check.message(), check.checkedAt());
    }

    /**
     * The index name appears only in the driver's own message, several causes
     * down, so the whole chain is searched rather than just the top exception.
     */
    private static boolean violatesNameIndex(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(NAME_INDEX)) {
                return true;
            }
        }
        return false;
    }
}
