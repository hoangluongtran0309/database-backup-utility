package com.hoangluongtran0309.dbbackup.core.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;

/**
 * Storage for registered targets.
 *
 * <p>There is no {@code existsByName}: uniqueness is the database's job, and
 * {@link #save} raises
 * {@link com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException}
 * when the write is rejected. A read-then-write check in a use case would
 * simply lose the race between two concurrent registrations.
 */
public interface DatabaseTargetRepository {

    /**
     * @throws com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException
     *         if another target already holds this name, ignoring case
     */
    DatabaseTarget save(DatabaseTarget target);

    /** All targets, ordered by name ignoring case. The order is part of the contract. */
    List<DatabaseTarget> findAll();

    Optional<DatabaseTarget> findById(UUID id);

    /** Silent when the id is unknown — deleting an absent target is not an error. */
    void deleteById(UUID id);

    /**
     * Stores the outcome of a connection probe against one target.
     *
     * <p>Deliberately narrower than {@link #save}: a probe must be incapable of
     * rewriting the target's credentials, so it updates only these columns
     * instead of persisting a whole reconstructed aggregate.
     */
    void recordConnectionCheck(UUID id, ConnectionCheck check);
}
