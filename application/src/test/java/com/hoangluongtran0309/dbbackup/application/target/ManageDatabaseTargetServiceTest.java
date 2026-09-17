package com.hoangluongtran0309.dbbackup.application.target;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService.BulkDeletionPreview;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreExecution;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

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
    private BackupExecutionRepository backups;

    @Mock
    private RestoreExecutionRepository restores;

    @Mock
    private BackupArtifactService artifacts;

    @Mock
    private EncryptionPort encryption;

    @Captor
    private ArgumentCaptor<DatabaseTarget> savedTarget;

    private ManageDatabaseTargetService service;

    @BeforeEach
    void setUp() {
        service = new ManageDatabaseTargetService(
                repository, backups, restores, artifacts, encryption, Clock.fixed(NOW, ZoneOffset.UTC));
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
                new RegisterTargetCommand("production",
                        com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL,
                        "127.0.0.1", 70000, "shop", "backup", "s3cr3t")))
                .isInstanceOf(InvalidTargetException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsABlankPasswordBeforeItCanBeEncryptedIntoSomethingNonBlank() {
        assertThatThrownBy(() -> new RegisterTargetCommand(
                "production",
                com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL,
                "127.0.0.1", 3306, "shop", "backup", "  "))
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

    /** ADR-014: a drill target goes, and the records of restores into it go with it. */
    @Test
    void removingATargetRemovesTheRestoresIntoItFirst() {
        DatabaseTarget target = stored();
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of());

        service.delete(target.getId(), false);

        InOrder order = inOrder(restores, repository);
        order.verify(restores).deleteForTarget(target.getId());
        order.verify(repository).deleteById(target.getId());
        verify(artifacts, never()).deleteAll(any());
    }

    /** Refused before anything is touched, so a refusal takes no history with it. */
    @Test
    void aTargetWithBackupsIsRefusedUnlessThatWasConfirmed() {
        DatabaseTarget target = stored();
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of(backupOf(target)));

        assertThatThrownBy(() -> service.delete(target.getId(), false))
                .isInstanceOf(TargetInUseException.class)
                .hasMessageContaining("production")
                .hasMessageContaining("still has backups");

        verify(artifacts, never()).deleteAll(any());
        verify(restores, never()).deleteForTarget(any());
        verify(repository, never()).deleteById(any());
    }

    /** ADR-015: its backups first, exactly as deleting them by hand would; then restores; then the row. */
    @Test
    void aConfirmedRemovalTakesTheBackupsThenTheRestoresThenTheTarget() {
        DatabaseTarget target = stored();
        BackupExecution first = backupOf(target);
        BackupExecution second = backupOf(target);
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of(first, second));

        service.delete(target.getId(), true);

        InOrder order = inOrder(artifacts, restores, repository);
        order.verify(artifacts).deleteAll(List.of(first.getId(), second.getId()));
        order.verify(restores).deleteForTarget(target.getId());
        order.verify(repository).deleteById(target.getId());
    }

    @Test
    void aTargetARestoreIsRunningIntoIsRefusedAndKeepsEverything() {
        DatabaseTarget target = stored();
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of(backupOf(target)));
        when(restores.findRunning()).thenReturn(List.of(
                RestoreExecution.started(UUID.randomUUID(), UUID.randomUUID(), target.getId(), NOW)));

        assertThatThrownBy(() -> service.delete(target.getId(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("restore into this target is running");

        verify(artifacts, never()).deleteAll(any());
        verify(repository, never()).deleteById(any());
    }

    /** A refusal from the backups themselves — one still running, say — stops the removal before the target row. */
    @Test
    void aRefusalFromItsBackupsLeavesTheTarget() {
        DatabaseTarget target = stored();
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of(backupOf(target)));
        when(artifacts.deleteAll(any())).thenThrow(new IllegalStateException("This backup is still running."));

        assertThatThrownBy(() -> service.delete(target.getId(), true))
                .isInstanceOf(IllegalStateException.class);

        verify(restores, never()).deleteForTarget(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void removingAnAbsentTargetIsNotAnError() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        service.delete(id, true);

        verify(restores, never()).deleteForTarget(any());
        verify(repository, never()).deleteById(any());
    }

    @Test
    void previewCountsItsBackupsAndEveryRestoreThatGoes() {
        DatabaseTarget target = stored();
        BackupExecution backup = backupOf(target);
        BulkDeletionPreview ownBackups = new BulkDeletionPreview(List.of(backup), 1, 8192L, 1L, null);
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of(backup));
        when(artifacts.previewDeletions(List.of(backup.getId()))).thenReturn(ownBackups);
        when(restores.countInvolvingTarget(target.getId())).thenReturn(4L);

        ManageDatabaseTargetService.TargetRemovalPreview preview = service.previewRemoval(target.getId());

        assertThat(preview.target()).isEqualTo(target);
        assertThat(preview.backups()).isEqualTo(ownBackups);
        assertThat(preview.restoreCount()).isEqualTo(4L);
        assertThat(preview.takesBackups()).isTrue();
        assertThat(preview.refused()).isFalse();
    }

    @Test
    void previewPassesOnWhyItsBackupsCannotGo() {
        DatabaseTarget target = stored();
        when(repository.findById(target.getId())).thenReturn(Optional.of(target));
        when(backups.findAllForTarget(target.getId())).thenReturn(List.of());
        when(artifacts.previewDeletions(List.of()))
                .thenReturn(new BulkDeletionPreview(List.of(), 0, 0L, 0L, "One of these backups is still running."));

        assertThat(service.previewRemoval(target.getId()).refusal()).contains("still running");
    }

    private static RegisterTargetCommand command() {
        return new RegisterTargetCommand("production",
                com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL,
                "127.0.0.1", 3306, "shop", "backup", "s3cr3t");
    }

    private static BackupExecution backupOf(DatabaseTarget target) {
        return BackupExecution.started(UUID.randomUUID(), target.getId(), NOW)
                .failed("boom", NOW.plusSeconds(1));
    }

    private static DatabaseTarget stored() {
        return DatabaseTarget.builder().engine(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.MYSQL)
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
