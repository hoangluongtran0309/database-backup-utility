package com.hoangluongtran0309.dbbackup.web.api;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

public record ApiEnvelope<T>(boolean ok, T data, ApiError error) {
    public static <T> ApiEnvelope<T> success(T data) { return new ApiEnvelope<>(true, data, null); }

    public static ApiEnvelope<Void> failure(String code, String message, String field) {
        return new ApiEnvelope<>(false, null, new ApiError(code, message, field, List.of()));
    }

    public static ApiEnvelope<Void> validationFailure(List<ApiFieldError> errors) {
        if (errors == null || errors.isEmpty()) {
            throw new IllegalArgumentException("Validation errors must not be empty");
        }
        List<ApiFieldError> copy = List.copyOf(errors);
        ApiFieldError first = copy.getFirst();
        return new ApiEnvelope<>(false, null,
                new ApiError("VALIDATION_ERROR", first.message(), first.field(), copy));
    }

    public record ApiError(
            String code,
            String message,
            String field,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<ApiFieldError> errors) { }

    public record ApiFieldError(String field, String message) { }
}
