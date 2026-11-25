package com.pulseops.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 400 — Bean Validation failures. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex,
                                                     HttpServletRequest request) {
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();

        ApiError body = new ApiError(
                400,
                "Bad Request",
                "Validation failed",
                request.getRequestURI(),
                Instant.now(),
                fieldErrors
        );
        return ResponseEntity.badRequest().body(body);
    }

    /** 401 — Wrong email or password (same message for both). */
    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ApiError> handleInvalidCredentials(InvalidCredentialsException ex,
                                                             HttpServletRequest request) {
        ApiError body = ApiError.of(401, "Unauthorized", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
    }

    /** 404 — monitor (or any resource) not found, or not owned by caller. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex,
                                                   HttpServletRequest request) {
        ApiError body = ApiError.of(404, "Not Found", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 400 — monitor quota exceeded (max 20 per user). */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ApiError> handleQuota(QuotaExceededException ex,
                                                HttpServletRequest request) {
        ApiError body = ApiError.of(400, "Bad Request", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** 409 — Duplicate email. */
    @ExceptionHandler(DuplicateEmailException.class)
    public ResponseEntity<ApiError> handleDuplicateEmail(DuplicateEmailException ex,
                                                         HttpServletRequest request) {
        ApiError body = ApiError.of(409, "Conflict", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** 422 — SSRF / forbidden target destination. */
    @ExceptionHandler(SsrfException.class)
    public ResponseEntity<ApiError> handleSsrf(SsrfException ex,
                                               HttpServletRequest request) {
        ApiError body = ApiError.of(422, "Unprocessable Content", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(body);
    }

    /** 429 — Rate limit exceeded (e.g. too many login attempts). */
    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ApiError> handleRateLimit(RateLimitExceededException ex,
                                                    HttpServletRequest request) {
        ApiError body = ApiError.of(429, "Too Many Requests", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(body);
    }

    /** 400 — Malformed JSON body (e.g. syntax error or wrong field type). */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException ex,
                                                        HttpServletRequest request) {
        ApiError body = ApiError.of(400, "Bad Request", "Malformed JSON request body", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** 400 — Invalid path/query parameter type (e.g. /monitors/abc instead of /monitors/1). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                       HttpServletRequest request) {
        ApiError body = ApiError.of(400, "Bad Request",
                "Invalid parameter value for '" + ex.getName() + "'", request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /** 405 — Wrong HTTP method for an endpoint. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        ApiError body = ApiError.of(405, "Method Not Allowed", ex.getMessage(), request.getRequestURI());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(body);
    }
}
