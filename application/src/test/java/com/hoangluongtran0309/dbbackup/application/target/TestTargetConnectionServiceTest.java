package com.hoangluongtran0309.dbbackup.application.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;

@ExtendWith(MockitoExtension.class)
class TestTargetConnectionServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:15:30Z");
    private static final UUID TARGET_ID = UUID.randomUUID();

    @Mock
    private DatabaseTargetRepository repository;

    @Mock
    private ConnectionTestPort connectionTest;

    @Mock
    private EngineAdapterRegistry adapters;

    @Mock
    private EncryptionPort encryption;

    @Captor
    private ArgumentCaptor<DatabaseConnection> connection;

    @Captor
    private ArgumentCaptor<ConnectionCheck> recorded;

    private TestTargetConnectionService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient().when(adapters.connectionTestFor(DatabaseEngine.MYSQL)).thenReturn(connectionTest);
        service = new TestTargetConnectionService(
                repository, adapters, encryption, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * The rule from ADR-002 made executable: the adapter is handed plain text
     * and never the key, and decryption happens exactly here.
     */
    @Test
    void decryptsThePasswordAndHandsTheAdapterPlainText() {
        givenTarget();
        when(encryption.decrypt("sealed")).thenReturn("s3cr3t");
        when(connectionTest.test(any())).thenReturn(ConnectionTestPort.Result.ok());

        service.test(TARGET_ID);

        verify(connectionTest).test(connection.capture());
        assertThat(connection.getValue().password()).isEqualTo("s3cr3t");
        assertThat(connection.getValue().host()).isEqualTo("db.internal");
        assertThat(connection.getValue().port()).isEqualTo(3307);
        assertThat(connection.getValue().database()).isEqualTo("shop");
        assertThat(connection.getValue().username()).isEqualTo("backup");
    }

    @Test
    void recordsASuccessfulCheckStampedWithTheInjectedClock() {
        givenTarget();
        when(encryption.decrypt(any())).thenReturn("s3cr3t");
        when(connectionTest.test(any())).thenReturn(ConnectionTestPort.Result.ok());

        ConnectionCheck returned = service.test(TARGET_ID);

        verify(repository).recordConnectionCheck(eq(TARGET_ID), recorded.capture());
        assertThat(recorded.getValue().successful()).isTrue();
        assertThat(recorded.getValue().checkedAt()).isEqualTo(NOW);
        assertThat(returned).isEqualTo(recorded.getValue());
    }

    @Test
    void recordsTheFailureMessageVerbatim() {
        givenTarget();
        when(encryption.decrypt(any())).thenReturn("s3cr3t");
        when(connectionTest.test(any())).thenReturn(
                ConnectionTestPort.Result.failed("ERROR 1045 (28000): Access denied for user 'backup'@'%'"));

        service.test(TARGET_ID);

        verify(repository).recordConnectionCheck(eq(TARGET_ID), recorded.capture());
        assertThat(recorded.getValue().successful()).isFalse();
        assertThat(recorded.getValue().message())
                .isEqualTo("ERROR 1045 (28000): Access denied for user 'backup'@'%'");
    }

    /**
     * A probe writes three columns through a targeted update. If it went
     * through save() instead, it would rewrite password_enc from a
     * reconstructed aggregate.
     */
    @Test
    void neverSavesTheWholeTarget() {
        givenTarget();
        when(encryption.decrypt(any())).thenReturn("s3cr3t");
        when(connectionTest.test(any())).thenReturn(ConnectionTestPort.Result.ok());

        service.test(TARGET_ID);

        verify(repository, never()).save(any());
    }

    @Test
    void sqliteNeverDecryptsCredentials() {
        DatabaseTarget sqlite = DatabaseTarget.builder()
                .engine(DatabaseEngine.SQLITE)
                .id(TARGET_ID)
                .name("local")
                .databaseName("shop.db")
                .createdAt(NOW)
                .build();
        when(repository.findById(TARGET_ID)).thenReturn(Optional.of(sqlite));
        when(adapters.connectionTestFor(DatabaseEngine.SQLITE)).thenReturn(connectionTest);
        when(connectionTest.test(any())).thenReturn(ConnectionTestPort.Result.ok());

        service.test(TARGET_ID);

        verify(connectionTest).test(connection.capture());
        assertThat(connection.getValue().database()).isEqualTo("shop.db");
        assertThat(connection.getValue().password()).isNull();
        verifyNoInteractions(encryption);
    }

    @Test
    void failsForAnUnknownTargetWithoutProbingAnything() {
        when(repository.findById(TARGET_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.test(TARGET_ID))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(TARGET_ID.toString());

        verifyNoInteractions(connectionTest, encryption);
    }

    private void givenTarget() {
        when(repository.findById(TARGET_ID)).thenReturn(Optional.of(DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
                .id(TARGET_ID)
                .name("production")
                .host("db.internal")
                .port(3307)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("sealed")
                .createdAt(NOW)
                .build()));
    }
}
