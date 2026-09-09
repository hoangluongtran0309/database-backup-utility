package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DatabaseTargetJpaRepository extends JpaRepository<DatabaseTargetEntity, UUID> {

    /**
     * Writes only the probe columns.
     *
     * <p>A bulk update rather than load-modify-save, so that recording a check
     * touches three columns and cannot possibly rewrite {@code password_enc}.
     * {@code clearAutomatically} keeps any already-loaded entity in the
     * persistence context from overwriting this on flush.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update DatabaseTargetEntity t
               set t.lastConnectionSuccessful = :successful,
                   t.lastConnectionMessage    = :message,
                   t.lastConnectionCheckedAt  = :checkedAt
             where t.id = :id
            """)
    int updateConnectionCheck(
            @Param("id") UUID id,
            @Param("successful") boolean successful,
            @Param("message") String message,
            @Param("checkedAt") Instant checkedAt);
}
