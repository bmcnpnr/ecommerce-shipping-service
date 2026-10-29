package com.ecommerce.shipping.exception;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/shipments/5/status");

    @BeforeEach
    void setCorrelationId() {
        MDC.put("correlationId", "cid-1");
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    private static void assertBody(ResponseEntity<ErrorResponse> response, HttpStatus status, String error, String message) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getStatus()).isEqualTo(status.value());
        assertThat(body.getError()).isEqualTo(error);
        assertThat(body.getMessage()).isEqualTo(message);
        assertThat(body.getPath()).isEqualTo("/api/v1/shipments/5/status");
        assertThat(body.getCorrelationId()).isEqualTo("cid-1");
        assertThat(body.getTimestamp()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(body.getFieldErrors()).isNull();
    }

    @Test
    void shipmentNotFound_is404() {
        assertBody(handler.notFound(new ShipmentNotFoundException("Shipment not found: 5"), request),
                HttpStatus.NOT_FOUND, "Not Found", "Shipment not found: 5");
    }

    @Test
    void invalidShipmentState_is400() {
        assertBody(handler.invalid(new InvalidShipmentStateException("Cannot update status of a DELIVERED shipment"), request),
                HttpStatus.BAD_REQUEST, "Bad Request", "Cannot update status of a DELIVERED shipment");
    }

    @Test
    void methodNotSupported_is405() {
        HttpRequestMethodNotSupportedException ex = new HttpRequestMethodNotSupportedException("DELETE");
        assertBody(handler.methodNotAllowed(ex, request), HttpStatus.METHOD_NOT_ALLOWED, "Method Not Allowed", "Method not allowed");
    }

    @Test
    void unreadableBody_is400() {
        HttpMessageNotReadableException ex = new HttpMessageNotReadableException("JSON parse error", new MockHttpInputMessage(new byte[0]));
        assertBody(handler.unreadable(ex, request), HttpStatus.BAD_REQUEST, "Bad Request", "Malformed request body");
    }

    @Test
    void missingParameterAndBindingErrors_are400() {
        MissingServletRequestParameterException missing = new MissingServletRequestParameterException("q", "String");
        assertBody(handler.missingParameter(missing, request), HttpStatus.BAD_REQUEST, "Bad Request", "Missing required parameter");

        ServletRequestBindingException binding = new ServletRequestBindingException("Missing header");
        assertBody(handler.binding(binding, request), HttpStatus.BAD_REQUEST, "Bad Request", "Invalid request");
    }

    @Test
    void anythingElse_is500_withGenericMessage_thatHidesTheCause() {
        assertBody(handler.general(new IllegalStateException("db password is hunter2"), request),
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    @Test
    void correlationIdIsNull_whenNoneInMdc() {
        MDC.clear();
        assertThat(handler.notFound(new ShipmentNotFoundException("x"), request).getBody().getCorrelationId()).isNull();
    }
}
