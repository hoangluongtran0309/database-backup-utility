package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import com.hoangluongtran0309.dbbackup.core.exception.DuplicateScheduleNameException;
import com.hoangluongtran0309.dbbackup.core.model.BackupSchedule;
import com.hoangluongtran0309.dbbackup.core.port.BackupScheduleRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class BackupScheduleRepositoryAdapter implements BackupScheduleRepository {

    private static final String NAME_INDEX = "ux_backup_schedules_name";
    private static final Sort BY_NAME = Sort.by(
            Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id"));

    private final BackupScheduleJpaRepository jpaRepository;

    @Override
    public BackupSchedule save(BackupSchedule schedule) {
        if (jpaRepository.existsByNameOtherThan(schedule.getName(), schedule.getId())) {
            throw new DuplicateScheduleNameException(schedule.getName());
        }
        try {
            return BackupScheduleMapper.toDomain(
                    jpaRepository.saveAndFlush(BackupScheduleMapper.toEntity(schedule)));
        } catch (DataIntegrityViolationException e) {
            if (violates(e, NAME_INDEX)) {
                throw new DuplicateScheduleNameException(schedule.getName());
            }
            throw e;
        }
    }

    @Override
    public List<BackupSchedule> findAll() {
        return jpaRepository.findAll(BY_NAME).stream().map(BackupScheduleMapper::toDomain).toList();
    }

    @Override
    public Optional<BackupSchedule> findById(UUID id) {
        return jpaRepository.findById(id).map(BackupScheduleMapper::toDomain);
    }

    @Override
    public long countForTarget(UUID targetId) {
        return jpaRepository.countByTargetId(targetId);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.deleteById(id);
        jpaRepository.flush();
    }

    private static boolean violates(Throwable e, String constraintName) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
                return true;
            }
        }
        return false;
    }
}
