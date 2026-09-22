package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface BackupScheduleJpaRepository extends JpaRepository<BackupScheduleEntity, UUID> {

    @Query("""
            select count(s) > 0 from BackupScheduleEntity s
             where lower(trim(s.name)) = lower(trim(:name))
               and s.id <> :id
            """)
    boolean existsByNameOtherThan(@Param("name") String name, @Param("id") UUID id);

    long countByTargetId(UUID targetId);
}
