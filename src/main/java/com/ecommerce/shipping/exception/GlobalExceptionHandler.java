package com.ecommerce.shipping.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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

    // Spring MVC raises these for malformed client requests. Without explicit
    // handlers they fall through to the Exception catch-all below and are
    // reported as 500, hiding the fact that the caller sent something invalid.
    @ExceptionHandler({
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<ErrorResponse> handleClientError(Exception ex, HttpServletRequest httpRequest) {
        HttpStatus status = (ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException)
                ? HttpStatus.METHOD_NOT_ALLOWED
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(ex.getMessage())
                .path(httpRequest.getRequestURI())
                .correlationId(MDC.get("correlationId"))
                .build());
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
