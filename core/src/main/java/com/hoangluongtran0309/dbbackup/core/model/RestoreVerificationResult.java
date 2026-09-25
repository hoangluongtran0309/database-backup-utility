package com.hoangluongtran0309.dbbackup.core.model;

/** Provider-neutral evidence returned after an isolated restore and health check. */
public record RestoreVerificationResult(int checkedObjects, String summary) {

    public RestoreVerificationResult {
        if (checkedObjects < 0) {
            throw new IllegalArgumentException("Checked object count cannot be negative");
        }
        if (summary == null || summary.isBlank()) {
            throw new IllegalArgumentException("Verification summary is required");
        }
        summary = summary.strip();
        if (summary.length() > RestoreVerificationExecution.MAX_MESSAGE_LENGTH) {
            throw new IllegalArgumentException("Verification summary is too long");
        }
    }
}
