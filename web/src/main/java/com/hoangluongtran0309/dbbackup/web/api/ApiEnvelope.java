package com.hoangluongtran0309.dbbackup.web.api;

public record ApiEnvelope<T>(boolean ok, T data, ApiError error) {
    public static <T> ApiEnvelope<T> success(T data) { return new ApiEnvelope<>(true, data, null); }
    public static ApiEnvelope<Void> failure(String code, String message, String field) {
        return new ApiEnvelope<>(false, null, new ApiError(code, message, field));
    }

    public record ApiError(String code, String message, String field) { }
}
