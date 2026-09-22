package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface StorageProfileJpaRepository extends JpaRepository<StorageProfileEntity, UUID> {
    @Query("select count(p) > 0 from StorageProfileEntity p where lower(trim(p.name)) = lower(trim(:name)) and p.id <> :id")
    boolean existsByNameOtherThan(@Param("name") String name, @Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update StorageProfileEntity p set p.lastConnectionSuccessful=:successful, p.lastConnectionMessage=:message, p.lastConnectionCheckedAt=:checkedAt, p.updatedAt=:checkedAt where p.id=:id")
    int updateConnectionCheck(@Param("id") UUID id, @Param("successful") boolean successful,
            @Param("message") String message, @Param("checkedAt") Instant checkedAt);
}
