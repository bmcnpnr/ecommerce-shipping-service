package com.ecommerce.shipping.exception;

import com.ecommerce.shipping.logging.LogSanitizer;
import com.ecommerce.shipping.logging.LogThrottle;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.dao.DataIntegrityViolationException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // A failure that repeats at request rate (a bug on a hot path, a dead database under load, one bad
    // client in a loop) writes its first occurrence per window and counts the rest: log volume follows
    // the number of distinct problems, not the traffic.
    private static final LogThrottle ERROR_LOG = new LogThrottle(10, TimeUnit.SECONDS);
    private static final LogThrottle UNAVAILABLE_LOG = new LogThrottle(10, TimeUnit.SECONDS);

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
        if (DependencyFailures.isUnavailable(ex)) {
            // A dead dependency is not a bug: 503 and one short line per window, not a stack trace per request.
            long suppressed = UNAVAILABLE_LOG.acquire(ex.getClass().getName());
            if (suppressed >= 0) {
                // the root cause matters: a rollback that failed on a dead connection replaces the original exception
                Throwable root = NestedExceptionUtils.getMostSpecificCause(ex);
                log.warn("Dependency unavailable on {} {} (correlationId={}): {}{}{}", req.getMethod(), req.getRequestURI(),
                        MDC.get("correlationId"), LogSanitizer.clean(ex.toString()),
                        root == ex ? "" : "; root cause " + LogSanitizer.clean(root.toString()),
                        LogThrottle.suppressedSuffix(suppressed));
            }
            return r(HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Service temporarily unavailable", req.getRequestURI());
        }
        long suppressed = ERROR_LOG.acquire(LogThrottle.keyOf(ex));
        if (suppressed >= 0) {
            log.error("Unhandled exception on {} {} (correlationId={}){}", req.getMethod(), req.getRequestURI(),
                    MDC.get("correlationId"), LogThrottle.suppressedSuffix(suppressed), ex);
        }
        return r(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> r(HttpStatus s, String e, String m, String p) {
        return ResponseEntity.status(s).body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(s.value()).error(e).message(m).path(p).correlationId(MDC.get("correlationId")).build());
    }
}
