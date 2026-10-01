package com.hoangluongtran0309.dbbackup.web.api;

import java.util.List;

import com.hoangluongtran0309.dbbackup.web.api.ApiEnvelope.ApiFieldError;

/** One invalid request body, carrying every independently detectable field error. */
final class ApiValidationException extends RuntimeException {
    private final List<ApiFieldError> errors;

    ApiValidationException(List<ApiFieldError> errors) {
        super(errors.getFirst().message());
        this.errors = List.copyOf(errors);
    }

    List<ApiFieldError> errors() {
        return errors;
    }
}
