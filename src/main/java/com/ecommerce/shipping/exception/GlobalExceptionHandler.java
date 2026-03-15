package com.ecommerce.shipping.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ShipmentNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ShipmentNotFoundException ex, HttpServletRequest req) {
        return r(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidShipmentStateException.class)
    public ResponseEntity<ErrorResponse> invalid(InvalidShipmentStateException ex, HttpServletRequest req) {
        return r(HttpStatus.BAD_REQUEST, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> general(Exception ex, HttpServletRequest req) {
        return r(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ResponseEntity<ErrorResponse> r(HttpStatus s, String e, String m, String p) {
        return ResponseEntity.status(s).body(ErrorResponse.builder().timestamp(LocalDateTime.now())
            .status(s.value()).error(e).message(m).path(p).correlationId(MDC.get("correlationId")).build());
    }
}
