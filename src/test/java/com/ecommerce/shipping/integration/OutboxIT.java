package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import com.ecommerce.shipping.service.ShipmentService;
import com.github.dockerjava.api.DockerClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The transactional outbox against real PostgreSQL and a real broker: an event is stored with the change
 * that caused it and published by the relay afterwards, so a change and its event succeed or fail
 * together and a broker that is down never fails or slows the request.
 */
class OutboxIT extends AbstractIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(60);

    @Autowired MockMvc mockMvc;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentService shipmentService;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired ObservationRegistry observations;
    @Autowired ThreadPoolTaskScheduler taskScheduler;

    private Shipment seed(ShipmentStatus status) {
        return shipmentRepository.saveAndFlush(Shipment.builder().orderId(nextOrderId()).customerId("outbox-it")
                .trackingNumber("TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                .status(status).carrier("DHL").originAddress("Warehouse").destinationAddress("Home")
                .estimatedDelivery(LocalDateTime.now().plusDays(5)).build());
    }

    private void admin(long shipmentId, String body, int expectedStatus) throws Exception {
        mockMvc.perform(patch("/api/v1/shipments/{id}/status", shipmentId)
                        .header("X-Username", "root").header("X-User-Role", "ADMIN")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expectedStatus));
    }

    private long outboxRows(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM outbox_event WHERE event_key = ?", Long.class, key);
    }

    private double counter(String name) {
        return meters.get(name).counter().count();
    }

    @Test
    void theRelayTicks_areNotObserved_soAnIdleServiceDoesNotExportTwoSpansASecond() {
        assertThat(Observation.createNotStarted("tasks.scheduled.execution", observations).isNoop()).isTrue();
        assertThat(Observation.createNotStarted("http.server.requests", observations).isNoop()).isFalse();
    }

    @Test
    void aShutdownWaitsForARelayRunThatIsMidBatch_insteadOfInterruptingIt() {
        assertThat(ReflectionTestUtils.getField(taskScheduler, "waitForTasksToCompleteOnShutdown")).isEqualTo(true);
        assertThat(ReflectionTestUtils.getField(taskScheduler, "awaitTerminationMillis")).isEqualTo(20_000L);
    }

    // (a) the change and the event are published together

    @Test
    void aStatusChange_andItsEvent_arePublishedTogether_andTheRowIsGoneOnceKafkaAcknowledged() throws Exception {
        Shipment shipment = seed(ShipmentStatus.PENDING);
        String key = String.valueOf(shipment.getOrderId());
        double publishedBefore = counter("outbox.events.published");

        admin(shipment.getId(), "{\"status\":\"PROCESSING\",\"location\":\"Istanbul hub\"}", 200);

        assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipment.getId())).isEqualTo("PROCESSING");
        List<Received> events = awaitRecords(SHIPMENT_UPDATED, key, r -> !r.isEmpty());
        assertThat(events).hasSize(1);
        assertThat(events.get(0).value().get("newStatus").asString()).isEqualTo("PROCESSING");
        assertThat(events.get(0).value().get("location").asString()).isEqualTo("Istanbul hub");
        await().atMost(WAIT).until(() -> outboxRows(key) == 0);
        assertThat(counter("outbox.events.published")).isGreaterThan(publishedBefore);
    }

    // (b) a rolled-back transaction publishes nothing

    @Test
    void aTransactionThatRollsBack_publishesNothing() {
        Shipment shipment = seed(ShipmentStatus.PENDING);
        String key = String.valueOf(shipment.getOrderId());

        // 300 characters do not fit shipment_events.location (255): the failure only shows when the
        // transaction flushes, after the event was already queued. The old fire-and-forget send had
        // announced the change by then.
        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(shipment.getId(), "PROCESSING", "x".repeat(300), null))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipment.getId())).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_events WHERE shipment_id = ?", Long.class, shipment.getId())).isZero();
        assertThat(outboxRows(key)).as("the queued event rolled back with the change").isZero();
        // barrier: a later, committed change on the same partition has been published, so the earlier one would have been too
        Shipment barrier = seed(ShipmentStatus.PENDING);
        shipmentService.updateShipmentStatus(barrier.getId(), "PROCESSING", null, null);
        String barrierKey = String.valueOf(barrier.getOrderId());
        assertThat(awaitRecords(SHIPMENT_UPDATED, null, r -> r.stream().anyMatch(x -> barrierKey.equals(x.key()))))
                .noneMatch(r -> key.equals(r.key()));
    }

    // (c) Kafka unreachable: the request is unaffected, the row waits, and is published once Kafka is back

    @Test
    void whenKafkaIsUnreachable_theRequestStillSucceedsPromptly_theRowWaits_andIsPublishedOnceKafkaIsBack() throws Exception {
        Shipment shipment = seed(ShipmentStatus.PENDING);
        String key = String.valueOf(shipment.getOrderId());
        DockerClient docker = KAFKA.getDockerClient();

        try (LogCapture logs = new LogCapture()) {
            docker.pauseContainerCmd(KAFKA.getContainerId()).exec(); // connections hang, as with a dead host
            try {
                long started = System.nanoTime();
                admin(shipment.getId(), "{\"status\":\"PROCESSING\"}", 200);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).as("the request does not wait for Kafka").isLessThan(Duration.ofSeconds(3));

                // only the relay's own thread waits for the unresponsive broker; the application carries on
                Thread.sleep(8_000);
                assertThat(outboxRows(key)).as("the event is still waiting").isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, shipment.getId())).isEqualTo("PROCESSING");
                // a second change meanwhile is just as quick
                admin(shipment.getId(), "{\"status\":\"SHIPPED\"}", 200);
                // outage so far: never a line (or a trace) per attempt
                assertThat(logs.problems()).as(logs.describe()).hasSizeLessThanOrEqualTo(2);
                assertThat(logs.stackTraceLines()).isZero();
            } finally {
                docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
            }

            // Kafka is back: both changes arrive, in order, once each
            List<Received> events = awaitRecords(SHIPMENT_UPDATED, key, r -> r.size() >= 2);
            assertThat(events).extracting(r -> r.value().get("newStatus").asString()).containsExactly("PROCESSING", "SHIPPED");
            await().atMost(WAIT).until(() -> outboxRows(key) == 0);
        }
    }

    // (d) a permanent failure parks the row, and the next one is still sent

    @Test
    void aRowThatCanNeverBePublished_isParked_andTheNextRowIsStillSent() {
        String bigKey = "parked-" + UUID.randomUUID();
        String goodKey = String.valueOf(nextOrderId());
        String insert = "INSERT INTO outbox_event (topic, event_key, payload, created_at) VALUES (?, ?, ?, now())";
        // 1.2 MB: larger than the producer's max.request.size (1 MB), so it can never be sent
        jdbc.update(insert, "shipment.updated", bigKey, "x".repeat(1_200_000));
        jdbc.update(insert, "shipment.updated", goodKey,
                "{\"shipmentId\":1,\"orderId\":" + goodKey + ",\"trackingNumber\":\"TRACK-X\",\"newStatus\":\"SHIPPED\",\"location\":null,\"updatedAt\":[2026,10,30,9,12,44]}");

        try (LogCapture logs = new LogCapture()) {
            await().atMost(WAIT).until(() -> outboxRows(goodKey) == 0);
            assertThat(awaitRecords(SHIPMENT_UPDATED, goodKey, r -> !r.isEmpty())).hasSize(1);

            assertThat(jdbc.queryForObject("SELECT parked_at IS NOT NULL FROM outbox_event WHERE event_key = ?", Boolean.class, bigKey)).isTrue();
            assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_key = ?", Integer.class, bigKey)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_event WHERE event_key = ?", String.class, bigKey))
                    .contains("RecordTooLargeException");
            // reported once, with the statement that unparks it
            assertThat(logs.problems()).as(logs.describe()).hasSize(1);
            assertThat(logs.problems().get(0).getFormattedMessage()).contains("parked").contains("UPDATE outbox_event SET parked_at = NULL WHERE id = ");

            // it is skipped from now on: no retries, no further log lines
            assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_event WHERE event_key = ?", Integer.class, bigKey)).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM outbox_event WHERE event_key = ?", bigKey);
        }
    }
}
