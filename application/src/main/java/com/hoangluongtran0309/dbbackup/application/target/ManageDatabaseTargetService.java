package com.hoangluongtran0309.dbbackup.application.target;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService;
import com.hoangluongtran0309.dbbackup.application.backup.BackupArtifactService.BulkDeletionPreview;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;
import com.hoangluongtran0309.dbbackup.core.model.BackupExecution;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.BackupExecutionRepository;
import com.hoangluongtran0309.dbbackup.core.port.BackupScheduleRepository;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;
import com.hoangluongtran0309.dbbackup.core.port.RestoreExecutionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Registering, editing, listing and removing backup targets.
 *
 * <p>A concrete class, not an interface with one implementation: the web layer
 * calls it directly. The ports it depends on are interfaces because they sit
 * at the edge of the hexagon and keep this class testable without a database;
 * an interface in front of <em>this</em> class would buy nothing.
 *
 * <p>This is also the only type in the system that calls {@link EncryptionPort}.
 */
@Service
@RequiredArgsConstructor
public class ManageDatabaseTargetService {

    /**
     * What removing a target would take with it.
     *
     * @param backups      its backups, and their files
     * @param restoreCount every restore record that goes: those into it, and
     *                     those made from its backups, each counted once
     * @param refusal      why it cannot be removed right now, or null if it can
     */
    public record TargetRemovalPreview(
            DatabaseTarget target,
            BulkDeletionPreview backups,
            long restoreCount,
            long scheduleCount,
            String refusal) {

        /** Whether its backups go too — the case that needs the name typed. */
        public boolean takesBackups() {
            return !backups.executions().isEmpty();
        }

        public boolean refused() {
            return refusal != null;
        }
    }

    private final DatabaseTargetRepository repository;
    private final BackupExecutionRepository backups;
    private final RestoreExecutionRepository restores;
    private final BackupScheduleRepository schedules;
    private final BackupArtifactService artifacts;
    private final EncryptionPort encryption;
    private final Clock clock;
    private final EngineAdapterRegistry engineAdapters;

    /**
     * @throws com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException
     *         if a field is missing or out of range
     * @throws com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException
     *         if the name is taken
     */
    public DatabaseTarget register(RegisterTargetCommand command) {
        if (!engineAdapters.supports(command.engine())) {
            throw new com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException(
                    "engine", "%s support is not enabled".formatted(command.engine().displayName()));
        }
        DatabaseTarget target = DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name(command.name())
                .engine(command.engine())
                .host(command.host())
                .port(command.port())
                .databaseName(command.database())
                .username(command.username())
                .authenticationDatabase(command.authenticationDatabase())
                .dataPumpDirectory(command.dataPumpDirectory())
                .passwordCiphertext(command.engine().isFileBased() ? null : encryption.encrypt(command.password()))
                .createdAt(clock.instant())
                .build();

        return repository.save(target);
    }

    /**
     * @throws NoSuchElementException if no target holds this id
     */
    public DatabaseTarget get(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("No database target with id " + id));
    }

    /**
     * Changes how a target is reached. Its backups stay with it.
     *
     * @throws NoSuchElementException if no target holds this id
     * @throws com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException
     *         if a field is missing or out of range
     * @throws com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException
     *         if the new name is taken by another target
     */
    public DatabaseTarget edit(UUID id, EditTargetCommand command) {
        DatabaseTarget current = get(id);
        if (current.getEngine().isFileBased() && command.changesPassword()) {
            throw new com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException(
                    "password", "Password is not used by SQLite targets");
        }
        if (current.getEngine() == com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.ORACLE
                && command.changesPassword()
                && (command.password().indexOf('\n') >= 0 || command.password().indexOf('\r') >= 0)) {
            throw new com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException(
                    "password", "Oracle password must not contain line breaks");
        }
        if (current.getEngine() == com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine.SQLSERVER
                && command.changesPassword()) {
            if (command.password().length() > 128) {
                throw new com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException(
                        "password", "SQL Server password must be at most 128 characters");
            }
            if (command.password().indexOf('\n') >= 0
                    || command.password().indexOf('\r') >= 0
                    || command.password().indexOf('\0') >= 0) {
                throw new com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException(
                        "password", "SQL Server password must not contain line breaks or NUL characters");
            }
        }
        DatabaseTarget edited = current.edited(
                command.name(),
                command.host(),
                command.port(),
                command.username(),
                command.authenticationDatabase(),
                command.dataPumpDirectory(),
                command.changesPassword() ? encryption.encrypt(command.password()) : null);

        return repository.save(edited);
    }

    public List<DatabaseTarget> listAll() {
        return repository.findAll();
    }

    public List<com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine> availableEngines() {
        return engineAdapters.availableEngines();
    }

    public boolean supports(com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine engine) {
        return engineAdapters.supports(engine);
    }

    /**
     * What removing a target would take with it.
     *
     * @throws NoSuchElementException if no target holds this id
     */
    public TargetRemovalPreview previewRemoval(UUID id) {
        DatabaseTarget target = get(id);
        BulkDeletionPreview ownBackups = artifacts.previewDeletions(idsOf(backups.findAllForTarget(id)));
        long scheduleCount = schedules.countForTarget(id);
        String refusal = ownBackups.refused() ? ownBackups.refusal() : restoreIntoRefusal(id);
        if (refusal == null && scheduleCount > 0) {
            refusal = scheduleCount == 1
                    ? "Delete this target's backup schedule before removing it."
                    : "Delete this target's %d backup schedules before removing it.".formatted(scheduleCount);
        }
        return new TargetRemovalPreview(
                target, ownBackups, restores.countInvolvingTarget(id), scheduleCount, refusal);
    }

    /**
     * Removes a target, its backups and their files, and every restore record
     * that mentions it.
     *
     * <p>The backups are the valuable thing, so they only go when the operator
     * has said so — by typing the target's name (ADR-015). Everything that
     * could refuse is checked before anything is deleted. The backups then go
     * one at a time exactly as a single deletion does (ADR-008); the restores
     * go in the use case, not by cascade (ADR-014).
     *
     * @param backupsConfirmed whether the operator confirmed that the target's
     *        backups go too. Not needed for a target that has none.
     * @throws TargetInUseException if it has backups and that was not confirmed
     * @throws IllegalStateException if one of its backups is running, or a
     *         restore from or into it is
     */
    public void delete(UUID id, boolean backupsConfirmed) {
        DatabaseTarget target = repository.findById(id).orElse(null);
        if (target == null) {
            return; // removing an absent target is not an error
        }
        List<UUID> backupIds = idsOf(backups.findAllForTarget(id));
        if (!backupIds.isEmpty() && !backupsConfirmed) {
            throw new TargetInUseException(target.getName());
        }
        long scheduleCount = schedules.countForTarget(id);
        if (scheduleCount > 0) {
            throw new IllegalStateException(scheduleCount == 1
                    ? "Delete this target's backup schedule before removing it."
                    : "Delete this target's %d backup schedules before removing it.".formatted(scheduleCount));
        }
        String refusal = restoreIntoRefusal(id);
        if (refusal != null) {
            throw new IllegalStateException(refusal);
        }
        if (!backupIds.isEmpty()) {
            artifacts.deleteAll(backupIds);
        }
        restores.deleteForTarget(id);
        // A backup started since the list was read still holds the row; the
        // foreign key refuses, and the adapter says so as TargetInUseException.
        repository.deleteById(id);
    }

    /** A restore writing into the target would record its outcome against a target that is gone. */
    private String restoreIntoRefusal(UUID targetId) {
        boolean restoring = restores.findRunning().stream().anyMatch(r -> r.getTargetId().equals(targetId));
        return restoring
                ? "A restore into this target is running. Wait for it to finish before removing the target."
                : null;
    }

    private static List<UUID> idsOf(List<BackupExecution> executions) {
        return executions.stream().map(BackupExecution::getId).toList();
    }
}
