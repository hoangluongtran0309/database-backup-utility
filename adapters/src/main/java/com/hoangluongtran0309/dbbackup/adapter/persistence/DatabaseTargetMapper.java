package com.hoangluongtran0309.dbbackup.adapter.persistence;

import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
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
        entity.setEngine(target.getEngine());
        entity.setHost(target.getHost());
        entity.setPort(target.getPort());
        entity.setDatabaseName(target.getDatabaseName());
        entity.setUsername(target.getUsername());
        entity.setPasswordEnc(target.getPasswordCiphertext());
        entity.setCreatedAt(target.getCreatedAt());

        // Carried across so that toEntity and toDomain are a true inverse pair.
        // Without this, saving a target that had already been probed would
        // silently wipe its last check.
        ConnectionCheck check = target.getLastConnectionCheck();
        if (check != null) {
            entity.setLastConnectionSuccessful(check.successful());
            entity.setLastConnectionMessage(check.message());
            entity.setLastConnectionCheckedAt(check.checkedAt());
        }
        return entity;
    }

    /**
     * The three columns move as a set: a row with a timestamp but no verdict
     * would be a half-written probe, so anything short of both is read as
     * "never tested".
     */
    private static ConnectionCheck toConnectionCheck(DatabaseTargetEntity entity) {
        if (entity.getLastConnectionSuccessful() == null || entity.getLastConnectionCheckedAt() == null) {
            return null;
        }
        return new ConnectionCheck(
                entity.getLastConnectionSuccessful(),
                entity.getLastConnectionMessage(),
                entity.getLastConnectionCheckedAt());
    }

    static DatabaseTarget toDomain(DatabaseTargetEntity entity) {
        return DatabaseTarget.builder()
                .id(entity.getId())
                .name(entity.getName())
                .engine(entity.getEngine())
                .host(entity.getHost())
                .port(entity.getPort())
                .databaseName(entity.getDatabaseName())
                .username(entity.getUsername())
                .passwordCiphertext(entity.getPasswordEnc())
                .createdAt(entity.getCreatedAt())
                .lastConnectionCheck(toConnectionCheck(entity))
                .build();
    }
}
