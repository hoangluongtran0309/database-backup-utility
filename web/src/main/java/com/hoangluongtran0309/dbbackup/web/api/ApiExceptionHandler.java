package com.hoangluongtran0309.dbbackup.web.api;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import com.hoangluongtran0309.dbbackup.core.exception.InvalidRetentionPolicyException;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidScheduleException;
import com.hoangluongtran0309.dbbackup.core.exception.InvalidTargetException;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateNotificationChannelNameException;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateScheduleNameException;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateStorageProfileNameException;
import com.hoangluongtran0309.dbbackup.core.exception.DuplicateTargetNameException;
import com.hoangluongtran0309.dbbackup.core.exception.NotificationChannelInUseException;
import com.hoangluongtran0309.dbbackup.core.exception.RestoreFailedException;
import com.hoangluongtran0309.dbbackup.core.exception.StorageProfileInUseException;
import com.hoangluongtran0309.dbbackup.core.exception.TargetInUseException;

import lombok.extern.slf4j.Slf4j;

@RestControllerAdvice(basePackageClasses = OperatorApiController.class)
@Slf4j
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<?> notFound(NoSuchElementException error) {
        return failure(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", error.getMessage(), null);
    }

    @ExceptionHandler(InvalidTargetException.class)
    ResponseEntity<?> invalidTarget(InvalidTargetException error) {
        return failure(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", error.getMessage(), error.getField());
    }

    @ExceptionHandler(InvalidScheduleException.class)
    ResponseEntity<?> invalidSchedule(InvalidScheduleException error) {
        return failure(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", error.getMessage(), error.getField());
    }

    @ExceptionHandler(InvalidRetentionPolicyException.class)
    ResponseEntity<?> invalidRetention(InvalidRetentionPolicyException error) {
        return failure(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", error.getMessage(), error.getField());
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    ResponseEntity<?> badRequest(Exception error) {
        return failure(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", useful(error), null);
    }

    @ExceptionHandler({IllegalStateException.class, DuplicateTargetNameException.class,
            DuplicateScheduleNameException.class, DuplicateStorageProfileNameException.class,
            DuplicateNotificationChannelNameException.class, TargetInUseException.class,
            StorageProfileInUseException.class, NotificationChannelInUseException.class,
            RestoreFailedException.class})
    ResponseEntity<?> conflict(RuntimeException error) {
        return failure(HttpStatus.CONFLICT, "CONFLICT", useful(error), null);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception error) {
        log.error("Unhandled operator API failure", error);
        return failure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "The server could not complete the request", null);
    }

    private static ResponseEntity<ApiEnvelope<Void>> failure(
            HttpStatus status, String code, String message, String field) {
        return ResponseEntity.status(status).body(ApiEnvelope.failure(code, message, field));
    }

    private static String useful(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank() ? error.getClass().getSimpleName() : error.getMessage();
    }
}
