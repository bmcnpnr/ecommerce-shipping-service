package com.ecommerce.shipping.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ShipmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ShipmentNotFoundException ex, HttpServletRequest req) {
        return r(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidShipmentStateException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidShipmentStateException ex, HttpServletRequest req) {
        return r(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validation(org.springframework.web.bind.MethodArgumentNotValidException ex, HttpServletRequest req) {
        java.util.List<ErrorResponse.FieldError> errors = ex.getBindingResult().getFieldErrors().stream()
            .map(f -> new ErrorResponse.FieldError(f.getField(), f.getDefaultMessage()))
            .collect(java.util.stream.Collectors.toList());
        return ResponseEntity.badRequest().body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(400).error("Bad Request").message("Validation failed").path(req.getRequestURI())
            .correlationId(MDC.get("correlationId")).fieldErrors(errors).build());
    }

    @ExceptionHandler(AuthenticationRequiredException.class)
    public ResponseEntity<ErrorResponse> unauthenticated(AuthenticationRequiredException ex, HttpServletRequest req) {
        return r(HttpStatus.UNAUTHORIZED, "Unauthorized", "Authentication required", req.getRequestURI());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> forbidden(AccessDeniedException ex, HttpServletRequest req) {
        return r(HttpStatus.FORBIDDEN, "Forbidden", "Access denied", req.getRequestURI());
    }

    @ExceptionHandler({IllegalStatusTransitionException.class, DuplicateShipmentException.class})
    public ResponseEntity<ErrorResponse> conflict(InvalidShipmentStateException ex, HttpServletRequest req) {
        return r(HttpStatus.CONFLICT, "Conflict", ex.getMessage(), req.getRequestURI());
    }

    // Client mistakes are 4xx with a fixed message per kind: never the parser text, Java types or class names.
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> methodNotAllowed(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "Method not allowed", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> unsupportedMediaType(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported Media Type", "Unsupported media type", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> notAcceptable(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.NOT_ACCEPTABLE, "Not Acceptable", "Not acceptable", req.getRequestURI());
    }

    @ExceptionHandler({org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class})
    public ResponseEntity<ErrorResponse> noResource(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.NOT_FOUND, "Not Found", "Resource not found", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.bind.MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> missingParameter(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.BAD_REQUEST, "Bad Request", "Missing required parameter", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.bind.ServletRequestBindingException.class)
    public ResponseEntity<ErrorResponse> binding(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadable(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.BAD_REQUEST, "Bad Request", "Malformed request body", req.getRequestURI());
    }

    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> typeMismatch(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        return r(HttpStatus.BAD_REQUEST, "Bad Request", "Invalid value for parameter '" + ex.getName().replaceAll("[^A-Za-z0-9_]", "") + "'", req.getRequestURI());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> integrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        if (isUniqueViolation(ex)) {
            return r(HttpStatus.CONFLICT, "Conflict", "Resource already exists", req.getRequestURI());
        }
        return general(ex, req);
    }

    private static boolean isUniqueViolation(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.dao.DuplicateKeyException) return true;
            if (t instanceof java.sql.SQLException sql && "23505".equals(sql.getSQLState())) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> general(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception on {} {} (correlationId={})", req.getMethod(), req.getRequestURI(), MDC.get("correlationId"), ex);
        return r(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> r(HttpStatus s, String e, String m, String p) {
        return ResponseEntity.status(s).body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(s.value()).error(e).message(m).path(p).correlationId(MDC.get("correlationId")).build());
    }
}
