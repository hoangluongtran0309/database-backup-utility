package com.hoangluongtran0309.dbbackup.application.target;

import java.time.Clock;
import java.util.NoSuchElementException;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.hoangluongtran0309.dbbackup.application.EngineAdapterRegistry;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseConnection;
import com.hoangluongtran0309.dbbackup.core.model.ConnectionCheck;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.port.ConnectionTestPort;
import com.hoangluongtran0309.dbbackup.core.port.DatabaseTargetRepository;
import com.hoangluongtran0309.dbbackup.core.port.EncryptionPort;

import lombok.RequiredArgsConstructor;

/**
 * Probes a registered target and remembers what happened.
 *
 * <p>This is where the stored password is decrypted, and the only place it
 * happens for this operation. The adapter is handed a {@link DatabaseConnection}
 * holding plain text and never sees the key, so the decrypt → use → discard
 * cycle occurs once, here, where it can be reviewed.
 */
@Service
@RequiredArgsConstructor
public class TestTargetConnectionService {

    private final DatabaseTargetRepository repository;
    private final EngineAdapterRegistry adapters;
    private final EncryptionPort encryption;
    private final Clock clock;

    /**
     * @throws NoSuchElementException if no target holds this id
     */
    public ConnectionCheck test(UUID targetId) {
        DatabaseTarget target = repository.findById(targetId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No database target with id " + targetId));

        ConnectionTestPort.Result result = adapters.connectionTestFor(target.getEngine()).test(
                DatabaseConnection.to(target, encryption.decrypt(target.getPasswordCiphertext())));

        ConnectionCheck check = result.successful()
                ? ConnectionCheck.passed(clock.instant())
                : ConnectionCheck.failed(result.message(), clock.instant());

        repository.recordConnectionCheck(targetId, check);
        return check;
    }
}
