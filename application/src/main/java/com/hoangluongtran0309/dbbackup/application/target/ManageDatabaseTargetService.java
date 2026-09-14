package com.hoangluongtran0309.dbbackup.application.target;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;

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

    private final DatabaseTargetRepository repository;
    private final EncryptionPort encryption;
    private final Clock clock;

    /**
     * @throws com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException
     *         if a field is missing or out of range
     * @throws com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException
     *         if the name is taken
     */
    public DatabaseTarget register(RegisterTargetCommand command) {
        DatabaseTarget target = DatabaseTarget.builder()
                .id(UUID.randomUUID())
                .name(command.name())
                .host(command.host())
                .port(command.port())
                .databaseName(command.database())
                .username(command.username())
                .passwordCiphertext(encryption.encrypt(command.password()))
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
        DatabaseTarget edited = get(id).edited(
                command.name(),
                command.host(),
                command.port(),
                command.username(),
                command.changesPassword() ? encryption.encrypt(command.password()) : null);

        return repository.save(edited);
    }

    public List<DatabaseTarget> listAll() {
        return repository.findAll();
    }

    public void delete(UUID id) {
        repository.deleteById(id);
    }
}
