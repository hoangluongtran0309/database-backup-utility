package com.hoangluongtran0309.dbbackup.core.port;

import java.nio.file.Path;
import java.util.UUID;

import com.hoangluongtran0309.dbbackup.core.model.DatabaseEngine;
import com.hoangluongtran0309.dbbackup.core.model.DatabaseTarget;
import com.hoangluongtran0309.dbbackup.core.model.RestoreVerificationResult;

/** Restores one logical artifact into an isolated database and validates it. */
public interface RestoreVerificationPort {

    DatabaseEngine engine();

    boolean isAvailable();

    RestoreVerificationResult verify(
            UUID verificationId, DatabaseTarget sourceTarget, Path artifact);

    /** Best-effort cleanup for work left behind by a previous application process. */
    default void abortInterrupted(UUID verificationId) {
        // File-only and local-process adapters have nothing durable to stop.
    }
}
