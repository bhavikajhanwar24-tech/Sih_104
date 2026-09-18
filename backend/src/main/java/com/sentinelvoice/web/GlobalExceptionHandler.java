package com.sentinelvoice.web;

import com.sentinelvoice.passport.ConsentRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(
            ResponseStatusException ex,
            HttpServletRequest request
    ) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        String message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
        log.error("response status cid={} path={} status={} msg={}", cid(), request.getRequestURI(), status.value(), message);
        return error(status, message, request);
    }

    @ExceptionHandler(ConsentRequiredException.class)
    public ResponseEntity<ApiError> handleConsentRequired(
            ConsentRequiredException ex,
            HttpServletRequest request
    ) {
        String message = ex.getMessage() == null ? "Consent required" : ex.getMessage();
        log.error("consent required cid={} path={} msg={}", cid(), request.getRequestURI(), message);
        return error(HttpStatus.FORBIDDEN, message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpServletRequest request
    ) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Request validation failed";
        }
        log.error("validation failed cid={} path={} msg={}", cid(), request.getRequestURI(), message);
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex,
            HttpServletRequest request
    ) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining("; "));
        if (message.isBlank()) {
            message = "Constraint violation";
        }
        log.error("constraint violation cid={} path={} msg={}", cid(), request.getRequestURI(), message);
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(
            IllegalArgumentException ex,
            HttpServletRequest request
    ) {
        String message = ex.getMessage() == null ? "Invalid request" : ex.getMessage();
        log.error("illegal argument cid={} path={} msg={}", cid(), request.getRequestURI(), message);
        return error(HttpStatus.BAD_REQUEST, message, request);
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiError> handleNotFound(
            NoSuchElementException ex,
            HttpServletRequest request
    ) {
        String message = ex.getMessage() == null ? "Resource not found" : ex.getMessage();
        log.error("not found cid={} path={} msg={}", cid(), request.getRequestURI(), message);
        return error(HttpStatus.NOT_FOUND, message, request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(
            IllegalStateException ex,
            HttpServletRequest request
    ) {
        String message = ex.getMessage() == null ? "Conflict" : ex.getMessage();
        log.error("illegal state cid={} path={} msg={}", cid(), request.getRequestURI(), message);
        return error(HttpStatus.CONFLICT, message, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(
            Exception ex,
            HttpServletRequest request
    ) {
        log.error("unhandled exception cid={} path={}", cid(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = new ApiError(
                Instant.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI(),
                cid()
        );
        return ResponseEntity.status(status).body(body);
    }

    private static String cid() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }
}
