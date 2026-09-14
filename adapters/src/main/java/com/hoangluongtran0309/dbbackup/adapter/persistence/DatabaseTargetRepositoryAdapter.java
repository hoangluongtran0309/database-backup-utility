package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class DatabaseTargetRepositoryAdapter implements DatabaseTargetRepository {

    /** Name of the unique index in V1, used to tell duplicates from other constraint failures. */
    private static final String NAME_INDEX = "ux_database_targets_name";

    /** The foreign key from V3; its violation means the target still has backups. */
    private static final String EXECUTION_FK = "backup_executions_target_id_fkey";

    /**
     * Ties are broken by id so that two targets whose names differ only by case
     * or surrounding space still come back in a stable order across calls.
     */
    private static final Sort BY_NAME = Sort.by(
            Sort.Order.asc("name").ignoreCase(),
            Sort.Order.asc("id"));

    private final DatabaseTargetJpaRepository jpaRepository;

    /**
     * A name already in use is checked for before the insert, so the ordinary
     * case — an operator typing a taken name — never reaches the index. A
     * violation there is logged by Hibernate at WARN, SQL state and all, as if
     * something had gone wrong. The catch stays for the one case the check
     * cannot see: two registrations of the same name racing each other.
     */
    @Override
    public DatabaseTarget save(DatabaseTarget target) {
        if (jpaRepository.existsByNameOtherThan(target.getName(), target.getId())) {
            throw new DuplicateTargetNameException(target.getName());
        }
        try {
            // saveAndFlush, not save: without the flush the constraint violation
            // would surface later at transaction commit, far from this catch.
            return DatabaseTargetMapper.toDomain(
                    jpaRepository.saveAndFlush(DatabaseTargetMapper.toEntity(target)));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, NAME_INDEX)) {
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

    /** Checked before the delete, for the same reason as the name in {@link #save}. */
    @Override
    public void deleteById(UUID id) {
        // The name is read before the delete so the failure can say which
        // target it was about; afterwards the row may be gone.
        String name = jpaRepository.findById(id)
                .map(DatabaseTargetEntity::getName)
                .orElse(null);
        if (name != null && jpaRepository.hasBackups(id)) {
            throw new TargetInUseException(name);
        }
        try {
            jpaRepository.deleteById(id);
            jpaRepository.flush();
        } catch (DataIntegrityViolationException e) {
            if (name != null && violates(e, EXECUTION_FK)) {
                throw new TargetInUseException(name);
            }
            throw e;
        }
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
     * The constraint name appears only in the driver's own message, several
     * causes down, so the whole chain is searched rather than just the top
     * exception.
     */
    private static boolean violates(Throwable e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
