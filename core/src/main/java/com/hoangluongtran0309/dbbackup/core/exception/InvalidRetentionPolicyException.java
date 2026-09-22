package com.hoangluongtran0309.dbbackup.core.exception;

import lombok.Getter;

/** A retention policy field does not describe a safe policy. */
@Getter
public class InvalidRetentionPolicyException extends RuntimeException {

    private final String field;

    public InvalidRetentionPolicyException(String field, String message) {
        super(message);
        this.field = field;
    }
}
