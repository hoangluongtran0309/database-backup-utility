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
    void anEditWithoutAPasswordKeepsTheStoredCiphertextAndEncryptsNothing() {
        DatabaseTarget stored = stored();
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));

        service.edit(stored.getId(), new EditTargetCommand("staging", "10.0.0.5", 3307, "reader", "  "));

        verify(repository).save(savedTarget.capture());
        DatabaseTarget saved = savedTarget.getValue();
        assertThat(saved.getId()).isEqualTo(stored.getId());
        assertThat(saved.getName()).isEqualTo("staging");
        assertThat(saved.address()).isEqualTo("10.0.0.5:3307/shop");
        assertThat(saved.getPasswordCiphertext()).isEqualTo("old-sealed");
        verify(encryption, never()).encrypt(any());
    }

    @Test
    void anEditWithAPasswordStoresItEncrypted() {
        DatabaseTarget stored = stored();
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));
        when(repository.save(any())).thenAnswer(call -> call.getArgument(0));
        when(encryption.encrypt("n3w")).thenReturn("new-sealed");

        service.edit(stored.getId(), new EditTargetCommand("production", "127.0.0.1", 3306, "backup", "n3w"));

        verify(repository).save(savedTarget.capture());
        assertThat(savedTarget.getValue().getPasswordCiphertext()).isEqualTo("new-sealed");
    }

    @Test
    void editingATargetThatIsGoneSaysSo() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.edit(id, new EditTargetCommand("p", "h", 3306, "u", null)))
                .isInstanceOf(NoSuchElementException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void anEditTheModelRejectsNeverReachesTheRepository() {
        DatabaseTarget stored = stored();
        when(repository.findById(stored.getId())).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service.edit(
                stored.getId(), new EditTargetCommand("production", "", 3306, "backup", null)))
                .isInstanceOf(InvalidTargetException.class)
                .extracting("field").isEqualTo("host");
        verify(repository, never()).save(any());
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

    private static DatabaseTarget stored() {
        return DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name("production")
                .host("127.0.0.1")
                .port(3306)
                .databaseName("shop")
                .username("backup")
                .passwordCiphertext("old-sealed")
                .createdAt(NOW)
                .build();
    }
}
