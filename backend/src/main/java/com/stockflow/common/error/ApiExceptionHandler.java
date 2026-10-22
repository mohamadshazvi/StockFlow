package com.stockflow.common.error;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record FieldError(String field, String message) {}
    public record ApiError(String code, String message, List<FieldError> fieldErrors, String traceId) {}

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    ResponseEntity<ApiError> constraints(jakarta.validation.ConstraintViolationException exception, HttpServletRequest request) {
        var fields = exception.getConstraintViolations().stream().map(v -> new FieldError(v.getPropertyPath().toString(), v.getMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED", "Check the highlighted fields.", fields, (String) request.getAttribute("traceId")));
    }

    @ExceptionHandler(org.springframework.dao.PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> busy(Exception exception, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new ApiError("RESOURCE_BUSY", "This record is busy. Refresh and try again.", List.of(), (String) request.getAttribute("traceId")));
    }

    @ExceptionHandler(ApiProblem.class)
    ResponseEntity<ApiError> business(ApiProblem exception, HttpServletRequest request) {
        return ResponseEntity.status(exception.status()).body(new ApiError(exception.code(), exception.getMessage(),
                List.of(), (String) request.getAttribute("traceId")));
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(org.springframework.dao.DataIntegrityViolationException exception, HttpServletRequest request) {
        boolean duplicateSku = false;
        boolean duplicateEmail = false;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "products_sku_key".equals(violation.getConstraintName())) duplicateSku = true;
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "app_users_email_key".equals(violation.getConstraintName())) duplicateEmail = true;
        }
        return ResponseEntity.status(409).body(new ApiError(duplicateSku ? "DUPLICATE_SKU" : duplicateEmail ? "DUPLICATE_EMAIL" : "DATA_CONFLICT",
                duplicateSku ? "A product with this SKU already exists." : duplicateEmail ? "This email is already in use." : "The change conflicts with existing data.",
                duplicateSku ? List.of(new FieldError("sku", "This SKU is already in use.")) : duplicateEmail ? List.of(new FieldError("email", "This email is already in use.")) : List.of(),
                (String) request.getAttribute("traceId")));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        var fields = exception.getBindingResult().getFieldErrors().stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage())).toList();
        return ResponseEntity.badRequest().body(new ApiError("VALIDATION_FAILED",
                "Check the highlighted fields.", fields, (String) request.getAttribute("traceId")));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception exception, HttpServletRequest request) {
        int status = exception instanceof org.springframework.http.converter.HttpMessageNotReadableException ? 400
                : exception instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ? 400
                : exception instanceof org.springframework.security.access.AccessDeniedException ? 403
                : exception instanceof org.springframework.security.core.AuthenticationException ? 401
                : exception instanceof ErrorResponse error ? error.getStatusCode().value() : 500;
        String code = switch (status) {
            case 401 -> "INVALID_CREDENTIALS";
            case 403 -> "FORBIDDEN";
            case 429 -> "TOO_MANY_ATTEMPTS";
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status < 500 ? "REQUEST_REJECTED" : "INTERNAL_ERROR";
        };
        if (status >= 500) {
            LOG.error("Request failed", exception);
        }
        String message = switch (status) {
            case 401 -> "Invalid email or password.";
            case 429 -> "Too many login attempts. Wait a minute and try again.";
            default -> status >= 500 ? "An unexpected error occurred." : "The request could not be processed.";
        };
        return ResponseEntity.status(status).body(new ApiError(code, message, List.of(),
                (String) request.getAttribute("traceId")));
    }
}
