package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.StorageProfile;

public interface StorageProfileRepository {
    StorageProfile save(StorageProfile profile);
    Optional<StorageProfile> findById(UUID id);
    List<StorageProfile> findAll();
    void recordConnectionCheck(UUID id, ConnectionCheck check);
    void deleteById(UUID id);
}
