package com.ecommerce.shipping.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;

import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/shipments/5/status");
    private final ch.qos.logback.classic.Logger handlerLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setCorrelationId() {
        MDC.put("correlationId", "cid-1");
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
        handlerLogger.detachAppender(logs);
        logs.stop();
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

    // ───────────────────────── dependency outages and log volume ─────────────────────────

    /** Distinct classes: the throttle keys on the exception class, so each test needs its own to see its first line. */
    static class FirstOutage extends TransientDataAccessResourceException {
        FirstOutage() {
            super("Hikari: Connection is not available, request timed out after 5000ms");
        }
    }

    static class SecondOutage extends TransientDataAccessResourceException {
        SecondOutage() {
            super("Hikari: Connection is not available, request timed out after 5000ms");
        }
    }

    static class ThirdOutage extends CannotCreateTransactionException {
        ThirdOutage() {
            super("Could not open JPA EntityManager for transaction");
        }
    }

    static Stream<Exception> unavailableDependencies() {
        return Stream.of(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", new SQLTransientConnectionException("pool timeout")),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"),
                new DataAccessResourceFailureException("connection lost"),
                new QueryTimeoutException("statement timed out"),
                new CannotAcquireLockException("lock timeout"),
                new JpaSystemException(new jakarta.persistence.PersistenceException(new SQLTransientConnectionException("pool timeout"))),
                new RuntimeException("wrapped", new CannotCreateTransactionException("db down")),
                // a backend killed or a database restarted mid-transaction: the failed rollback replaces the original exception
                new TransactionSystemException("Could not roll back JPA transaction",
                        new org.hibernate.TransactionException("Unable to rollback against JDBC Connection", new SQLException("This connection has been closed."))),
                new org.hibernate.TransactionException("Unable to commit against JDBC Connection", new SQLException("connection reset")),
                new TimeoutException("Topic shipment.created not present in metadata after 5000 ms."));
    }

    @ParameterizedTest
    @MethodSource("unavailableDependencies")
    void aDependencyThatDoesNotAnswer_is503_withAFixedMessage(Exception outage) {
        ResponseEntity<ErrorResponse> response = handler.general(outage, request);

        assertBody(response, HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "Service temporarily unavailable");
    }

    @Test
    void aFailedCommitThatIsNotAboutTheConnection_isStillAServerError() {
        ResponseEntity<ErrorResponse> response = handler.general(
                new TransactionSystemException("Could not commit JPA transaction", new IllegalStateException("a bug, not an outage")), request);

        assertBody(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    @Test
    void theOutageLine_namesTheRootCause_whichAFailedRollbackWouldOtherwiseHide() {
        class RollbackOutage extends TransactionSystemException {
            RollbackOutage() {
                super("Could not roll back JPA transaction", new SQLException("FATAL: terminating connection due to administrator command"));
            }
        }

        handler.general(new RollbackOutage(), request);

        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.get(0).getFormattedMessage()).contains("Could not roll back JPA transaction")
                .contains("root cause java.sql.SQLException: FATAL: terminating connection due to administrator command");
    }

    @Test
    void aUniqueConstraintRace_staysAConflict_notAnOutage() {
        ResponseEntity<ErrorResponse> response = handler.integrity(new DuplicateKeyException("uq_shipments_order_id"), request);

        assertBody(response, HttpStatus.CONFLICT, "Conflict", "Resource already exists");
    }

    @Test
    void aConstraintViolationThatIsNotUnique_isStillAServerError() {
        ResponseEntity<ErrorResponse> response = handler.integrity(new DataIntegrityViolationException("value too long"), request);

        assertBody(response, HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
    }

    @Test
    void aDeadDatabaseUnderLoad_logsOneShortLine_notAStackTracePerRequest() {
        for (int i = 0; i < 1_000; i++) {
            assertThat(handler.general(new FirstOutage(), request).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        assertThat(logs.list).hasSize(1);
        ILoggingEvent line = logs.list.get(0);
        assertThat(line.getLevel()).isEqualTo(Level.WARN);
        assertThat(line.getThrowableProxy()).as("no stack trace for a dead dependency").isNull();
        assertThat(line.getFormattedMessage()).contains("Dependency unavailable on PATCH /api/v1/shipments/5/status")
                .contains("correlationId=cid-1").contains("Connection is not available");
    }

    @Test
    void outageLogLines_areReportedSeparatelyPerFailureKind() {
        handler.general(new SecondOutage(), request);
        handler.general(new ThirdOutage(), request);

        assertThat(logs.list).hasSize(2);
    }

    /** A bug on a hot path: the same failure site 1,000 times in a row. */
    @Test
    void aBugOnAHotPath_logsItsStackTraceOnce_andTheClientStillGetsA500() {
        for (int i = 0; i < 1_000; i++) {
            RuntimeException bug = new IllegalStateException("db password is hunter2"); // one throw site
            assertBody(handler.general(bug, request), HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "An unexpected error occurred");
        }

        List<ILoggingEvent> errors = logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
        assertThat(errors).hasSize(1);
        assertThat(errors.get(0).getThrowableProxy()).as("the first occurrence keeps its stack trace").isNotNull();
        assertThat(errors.get(0).getFormattedMessage()).contains("Unhandled exception on PATCH /api/v1/shipments/5/status");
    }

    @Test
    void differentBugs_areLoggedEach() {
        handler.general(new IllegalArgumentException("one"), request);
        handler.general(new UnsupportedOperationException("two"), request);

        assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR)).hasSize(2);
    }
}
