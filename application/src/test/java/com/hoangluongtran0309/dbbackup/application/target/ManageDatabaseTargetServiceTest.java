package com.hoangluongtran0309.dbbackup.application.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;

/**
 * Plain JUnit with mocked ports: no Spring context, no Docker. That this is
 * possible is the whole reason {@code application} depends on {@code core}
 * alone.
 */
@ExtendWith(MockitoExtension.class)
class ManageDatabaseTargetServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:15:30Z");

    @Mock
    private DatabaseTargetRepository repository;

    @Mock
    private EncryptionPort encryption;

    @Captor
    private ArgumentCaptor<DatabaseTarget> savedTarget;

    private ManageDatabaseTargetService service;

    @BeforeEach
    void setUp() {
        service = new ManageDatabaseTargetService(
                repository, encryption, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void storesTheEncryptedPasswordAndNeverThePlaintext() {
        when(encryption.encrypt("s3cr3t")).thenReturn("sealed");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.register(command());

        verify(repository).save(savedTarget.capture());
        assertThat(savedTarget.getValue().getPasswordCiphertext()).isEqualTo("sealed");
        assertThat(savedTarget.getValue().getPasswordCiphertext()).isNotEqualTo("s3cr3t");
    }

    @Test
    void stampsTheTargetWithTheInjectedClock() {
        when(encryption.encrypt(any())).thenReturn("sealed");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.register(command());

        verify(repository).save(savedTarget.capture());
        assertThat(savedTarget.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(savedTarget.getValue().getId()).isNotNull();
    }

    @Test
    void copiesEveryFieldFromTheCommand() {
        when(encryption.encrypt(any())).thenReturn("sealed");
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.register(command());

        verify(repository).save(savedTarget.capture());
        DatabaseTarget target = savedTarget.getValue();
        assertThat(target.getName()).isEqualTo("production");
        assertThat(target.getHost()).isEqualTo("127.0.0.1");
        assertThat(target.getPort()).isEqualTo(3306);
        assertThat(target.getDatabaseName()).isEqualTo("shop");
        assertThat(target.getUsername()).isEqualTo("backup");
    }

    @Test
    void doesNotReachTheRepositoryWhenTheModelRejectsTheValues() {
        when(encryption.encrypt(any())).thenReturn("sealed");

        assertThatThrownBy(() -> service.register(
                new RegisterTargetCommand("production", "127.0.0.1", 70000, "shop", "backup", "s3cr3t")))
                .isInstanceOf(InvalidTargetException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsABlankPasswordBeforeItCanBeEncryptedIntoSomethingNonBlank() {
        assertThatThrownBy(() -> new RegisterTargetCommand(
                "production", "127.0.0.1", 3306, "shop", "backup", "  "))
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("password");
    }

    @Test
    void listAllDelegatesToTheRepository() {
        when(repository.findAll()).thenReturn(List.of());

        assertThat(service.listAll()).isEmpty();
        verify(repository).findAll();
    }

    @Test
    void deleteDelegatesToTheRepository() {
        UUID id = UUID.randomUUID();

        service.delete(id);

        verify(repository).deleteById(id);
    }

    private static RegisterTargetCommand command() {
        return new RegisterTargetCommand("production", "127.0.0.1", 3306, "shop", "backup", "s3cr3t");
    }
}
