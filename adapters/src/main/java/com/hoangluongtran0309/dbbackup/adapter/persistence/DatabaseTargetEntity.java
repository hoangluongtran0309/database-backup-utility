package com.hoangluongtran0309.dbbackup.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA row for {@code database_targets}.
 *
 * <p>Mutable and setter-bearing, unlike the domain model, because that is what
 * Hibernate needs. Keeping the two apart is the point: the mutability stops at
 * this package, and nothing outside it ever sees this class.
 */
@Entity
@Table(name = "database_targets")
@Getter
@Setter
@NoArgsConstructor
class DatabaseTargetEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DatabaseEngine engine;

    @Column(nullable = false, length = 255)
    private String host;

    @Column(nullable = false)
    private int port;

    @Column(name = "database_name", nullable = false, length = 64)
    private String databaseName;

    @Column(nullable = false, length = 63)
    private String username;

    @Column(name = "password_enc", nullable = false, columnDefinition = "text")
    private String passwordEnc;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // Null as a set until the target is first probed.
    @Column(name = "last_connection_successful")
    private Boolean lastConnectionSuccessful;

    @Column(name = "last_connection_message", length = 500)
    private String lastConnectionMessage;

    @Column(name = "last_connection_checked_at")
    private Instant lastConnectionCheckedAt;
}
