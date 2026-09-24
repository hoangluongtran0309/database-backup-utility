package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.hoangluongtran0309.dbbackup.core.exception.DuplicateNotificationChannelNameException;
import com.hoangluongtran0309.dbbackup.core.exception.NotificationChannelInUseException;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.NotificationChannel;
import com.hoangluongtran0309.dbbackup.core.port.NotificationChannelRepository;

import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
class NotificationChannelRepositoryAdapter implements NotificationChannelRepository {
    private final NotificationChannelJpaRepository repository;

    @Override public NotificationChannel save(NotificationChannel channel) {
        if (repository.existsByNameOtherThan(channel.getName(), channel.getId())) {
            throw new DuplicateNotificationChannelNameException(channel.getName());
        }
        try {
            return NotificationChannelMapper.toDomain(
                    repository.saveAndFlush(NotificationChannelMapper.toEntity(channel)));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateNotificationChannelNameException(channel.getName());
        }
    }
    @Override public Optional<NotificationChannel> findById(UUID id) {
        return repository.findById(id).map(NotificationChannelMapper::toDomain);
    }
    @Override public List<NotificationChannel> findAll() {
        return repository.findAll(Sort.by(Sort.Order.asc("name").ignoreCase(), Sort.Order.asc("id")))
                .stream().map(NotificationChannelMapper::toDomain).toList();
    }
    @Override @Transactional public void recordConnectionCheck(UUID id, ConnectionCheck check) {
        repository.updateConnectionCheck(id, check.successful(), check.message(), check.checkedAt());
    }
    @Override public void deleteById(UUID id) {
        String name = repository.findById(id).map(NotificationChannelEntity::getName).orElse(id.toString());
        try {
            repository.deleteById(id);
            repository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new NotificationChannelInUseException(name);
        }
    }
}
