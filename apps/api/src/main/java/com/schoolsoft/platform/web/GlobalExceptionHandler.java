package com.schoolsoft.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("bad_request", ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException ex) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
            .map(e -> e.getField() + " " + e.getDefaultMessage())
            .reduce((a, b) -> a + "; " + b)
            .orElse("The request is not valid");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("validation_failed", detail));
    }

    /*
     * The request never reached a handler that could judge it: a parameter
     * missing or empty, one that does not parse as its type, a body that is not
     * JSON. Each is the caller's mistake, and without these the catch-all
     * below answered 500 — "the server is broken" — for a typo in a query
     * string, and logged a stack trace for it.
     */

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("bad_request", "Missing required parameter '" + ex.getParameterName() + "'"));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("bad_request", "Parameter '" + ex.getName() + "' is not valid"));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiError.of("bad_request", "The request body could not be read"));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiError.of("method_not_allowed", ex.getMessage()));
    }

    /** A status a handler chose on purpose — a rate limit's 429, say — with its own reason. */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> status(ResponseStatusException ex) {
        String code = ex.getStatusCode().value() == 429 ? "rate_limited" : "error";
        return ResponseEntity.status(ex.getStatusCode())
            .body(ApiError.of(code, ex.getReason() == null ? "Request refused" : ex.getReason()));
    }

    /** A path no controller maps. Spring reports it as a missing static resource. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noSuchPath(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError.of("not_found", "No such endpoint"));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiError.of("not_found", ex.getMessage()));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError.of("forbidden", ex.getMessage()));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> conflict(ConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("conflict", ex.getMessage()));
    }

    /**
     * A constraint the application did not pre-check — an academic-year overlap
     * racing another writer, a duplicate natural key, a trigger refusing a term
     * outside its year. The database is the authority; the caller gets a 409
     * with the reason rather than a 500 with "Unexpected error".
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> conflict(DataIntegrityViolationException ex) {
        String detail = ex.getMostSpecificCause().getMessage();
        log.warn("Constraint violation: {}", detail);
        return ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiError.of("constraint_violation", detail));
    }

    /**
     * A {@code @PreAuthorize} refusal. Method security throws this from inside
     * the controller invocation, so this advice sees it — and without an
     * explicit handler the catch-all below would turn every denied call into a
     * 500, which reads to the client as "the server is broken" rather than
     * "you may not do this". Deliberately says nothing about which permission
     * was missing: that is a map of the system, drawn for whoever was refused.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiError.of("forbidden", "You do not have permission to do this"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> generic(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiError.of("internal_error", "Unexpected error"));
    }
}
