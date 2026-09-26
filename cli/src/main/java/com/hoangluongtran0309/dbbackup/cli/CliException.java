package com.hoangluongtran0309.dbbackup.cli;

import com.fasterxml.jackson.databind.JsonNode;

final class CliException extends RuntimeException {
    private final int exitCode;
    private final JsonNode envelope;

    CliException(int exitCode, String message) {
        this(exitCode, message, null);
    }

    CliException(int exitCode, String message, JsonNode envelope) {
        super(message);
        this.exitCode = exitCode;
        this.envelope = envelope;
    }

    int exitCode() {
        return exitCode;
    }

    JsonNode envelope() {
        return envelope;
    }
}
