package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

/**
 * Hand-written both ways. A mapping framework would hide exactly the thing
 * worth seeing here — which persisted column carries the ciphertext.
 */
final class DatabaseTargetMapper {

    private DatabaseTargetMapper() {
    }

    static DatabaseTargetEntity toEntity(DatabaseTarget target) {
        DatabaseTargetEntity entity = new DatabaseTargetEntity();
        entity.setId(target.getId());
        entity.setName(target.getName());
        entity.setHost(target.getHost());
        entity.setPort(target.getPort());
        entity.setDatabaseName(target.getDatabaseName());
        entity.setUsername(target.getUsername());
        entity.setPasswordEnc(target.getPasswordCiphertext());
        entity.setCreatedAt(target.getCreatedAt());
        return entity;
    }

    static DatabaseTarget toDomain(DatabaseTargetEntity entity) {
        return DatabaseTarget.builder()
                .id(entity.getId())
                .name(entity.getName())
                .host(entity.getHost())
                .port(entity.getPort())
                .databaseName(entity.getDatabaseName())
                .username(entity.getUsername())
                .passwordCiphertext(entity.getPasswordEnc())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
