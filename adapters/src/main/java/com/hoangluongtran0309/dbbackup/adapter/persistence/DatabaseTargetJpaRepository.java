package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface DatabaseTargetJpaRepository extends JpaRepository<DatabaseTargetEntity, UUID> {

    /**
     * Whether a target other than {@code id} already has this name, compared
     * the way the unique index in V1 compares it: {@code lower(btrim(name))}.
     */
    @Query("""
            select count(t) > 0 from DatabaseTargetEntity t
             where lower(trim(t.name)) = lower(trim(:name))
               and t.id <> :id
            """)
    boolean existsByNameOtherThan(@Param("name") String name, @Param("id") UUID id);

    /** Whether any backup execution still refers to the target — the V3 foreign key. */
    @Query("select count(e) > 0 from BackupExecutionEntity e where e.targetId = :id")
    boolean hasBackups(@Param("id") UUID id);

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
