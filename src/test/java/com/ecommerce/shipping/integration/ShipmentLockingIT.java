package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import ch.qos.logback.classic.spi.ILoggingEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;

/**
 * Status changes read the current status and decide from it, so concurrent changes of one shipment must be
 * serialised (row lock) and the wait for that lock must be bounded.
 */
class ShipmentLockingIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbc;

    private ExecutorService executor;

    @BeforeEach
    void startExecutor() {
        executor = Executors.newFixedThreadPool(4);
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
    }

    private Shipment seed(ShipmentStatus status) {
        return shipmentRepository.saveAndFlush(Shipment.builder().orderId(nextOrderId()).customerId("locking-it")
                .trackingNumber("TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                .status(status).carrier("DHL").originAddress("Warehouse").destinationAddress("Home")
                .estimatedDelivery(LocalDateTime.now().plusDays(5)).build());
    }

    private MvcResult changeStatus(long shipmentId, String status) throws Exception {
        return mockMvc.perform(patch("/api/v1/shipments/{id}/status", shipmentId)
                .header("X-Username", "root").header("X-User-Role", "ADMIN")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}")).andReturn();
    }

    private long historyRows(long shipmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM shipment_events WHERE shipment_id = ?", Long.class, shipmentId);
    }

    private static Connection holdRowLock(long shipmentId) throws SQLException {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), "shippinguser", "shippingpass");
        c.setAutoCommit(false);
        try (Statement st = c.createStatement()) {
            st.execute("SELECT id FROM shipments WHERE id = " + shipmentId + " FOR UPDATE");
        }
        return c;
    }

    @Test
    void aStatusChange_waitsForTheRowLock_thenSeesTheCommittedStatus() throws Exception {
        Shipment shipment = seed(ShipmentStatus.PENDING);

        try (Connection otherAdmin = holdRowLock(shipment.getId())) {
            Future<MvcResult> second = executor.submit(() -> changeStatus(shipment.getId(), "PROCESSING"));
            Thread.sleep(1_500);
            assertThat(second.isDone()).as("blocked on the row lock").isFalse();

            // the other admin's change (PENDING to PROCESSING) commits and releases the row
            try (Statement st = otherAdmin.createStatement()) {
                st.execute("UPDATE shipments SET status = 'PROCESSING' WHERE id = " + shipment.getId());
            }
            otherAdmin.commit();

            // it now sees PROCESSING, and PROCESSING to PROCESSING is not a legal move: refused, not applied twice
            assertThat(second.get(15, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);
        }
        assertThat(historyRows(shipment.getId())).isZero();
    }

    @Test
    void twoAdminsChangingOneShipmentAtOnce_oneWins_theOtherIsRefused_andOneEventIsPublished() throws Exception {
        for (int round = 0; round < 3; round++) {
            Shipment shipment = seed(ShipmentStatus.PENDING);
            CyclicBarrier together = new CyclicBarrier(2);
            List<Future<MvcResult>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(executor.submit(() -> {
                    together.await(10, TimeUnit.SECONDS);
                    return changeStatus(shipment.getId(), "PROCESSING");
                }));
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<MvcResult> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS).getResponse().getStatus());
            }

            assertThat(statuses).as("round %d", round).containsExactlyInAnyOrder(200, 409);
            assertThat(historyRows(shipment.getId())).as("one history row").isEqualTo(1);
            String key = String.valueOf(shipment.getOrderId());
            assertThat(awaitRecords(SHIPMENT_UPDATED, key, r -> !r.isEmpty())).as("one event").hasSize(1);
        }
    }

    /**
     * A killed backend (or a database restart) while a request is inside its transaction: the rollback then fails
     * too, and its exception replaces the original one. It used to reach the client as a 500, with a trace of several
     * hundred lines from the transaction interceptor on top of the handler's own.
     */
    @Test
    void aDatabaseConnectionThatDiesMidRequest_is503_withoutAHugeStackTrace() throws Exception {
        Shipment shipment = seed(ShipmentStatus.PENDING);

        try (Connection lockHolder = holdRowLock(shipment.getId()); LogCapture logs = new LogCapture()) {
            Future<MvcResult> request = executor.submit(() -> changeStatus(shipment.getId(), "PROCESSING"));
            // the request's backend now waits for our lock inside its transaction: kill it
            Long pid = await().atMost(Duration.ofSeconds(4)).pollInterval(Duration.ofMillis(50)).until(
                    () -> jdbc.queryForList("SELECT pid FROM pg_stat_activity WHERE datname = current_database() "
                            + "AND wait_event_type = 'Lock' AND pid <> pg_backend_pid()", Long.class).stream().findFirst().orElse(null),
                    java.util.Objects::nonNull);
            jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid.intValue());

            MvcResult result = request.get(30, TimeUnit.SECONDS);

            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(503);
            assertThat(result.getResponse().getContentAsString()).contains("Service temporarily unavailable");
            // Hikari reports the original database error once per broken connection (here one, at most one per pooled
            // connection when a database restarts); everything of ours is one line, with the root cause and no trace
            List<ILoggingEvent> ours = logs.problems().stream()
                    .filter(e -> !e.getLoggerName().equals("com.zaxxer.hikari.pool.ProxyConnection")).toList();
            assertThat(ours).as(logs.describe()).hasSize(1);
            assertThat(ours.get(0).getThrowableProxy()).isNull();
            assertThat(ours.get(0).getFormattedMessage()).contains("Dependency unavailable").contains("root cause");
            assertThat(logs.problems().size() - ours.size()).isLessThanOrEqualTo(1);
        }
        assertThat(historyRows(shipment.getId())).isZero();
        assertThat(changeStatus(shipment.getId(), "PROCESSING").getResponse().getStatus()).as("the pool recovers").isEqualTo(200);
    }

    @Test
    void aLockThatIsHeldTooLong_failsTheRequestAs503_after5Seconds_insteadOfHangingIt() throws Exception {
        Shipment shipment = seed(ShipmentStatus.PENDING);

        try (Connection stuckTransaction = holdRowLock(shipment.getId())) {
            long started = System.nanoTime();
            MvcResult result = changeStatus(shipment.getId(), "PROCESSING");
            Duration waited = Duration.ofNanos(System.nanoTime() - started);

            assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(503);
            assertThat(result.getResponse().getContentAsString()).contains("Service temporarily unavailable");
            assertThat(waited).as("lock.timeout is 5 s").isBetween(Duration.ofSeconds(4), Duration.ofSeconds(9));
        }

        // once the lock is gone the same request works, and nothing was half-applied
        assertThat(historyRows(shipment.getId())).isZero();
        assertThat(changeStatus(shipment.getId(), "PROCESSING").getResponse().getStatus()).isEqualTo(200);
    }
}
