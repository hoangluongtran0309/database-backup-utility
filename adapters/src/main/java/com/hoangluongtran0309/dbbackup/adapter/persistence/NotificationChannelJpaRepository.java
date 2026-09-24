package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface NotificationChannelJpaRepository extends JpaRepository<NotificationChannelEntity, UUID> {
    @Query("select count(c) > 0 from NotificationChannelEntity c where lower(trim(c.name)) = lower(trim(:name)) and c.id <> :id")
    boolean existsByNameOtherThan(@Param("name") String name, @Param("id") UUID id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update NotificationChannelEntity c set c.lastConnectionSuccessful=:successful, c.lastConnectionMessage=:message, c.lastConnectionCheckedAt=:checkedAt where c.id=:id")
    int updateConnectionCheck(@Param("id") UUID id, @Param("successful") boolean successful,
            @Param("message") String message, @Param("checkedAt") Instant checkedAt);
}
