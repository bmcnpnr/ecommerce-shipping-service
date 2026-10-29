package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentEvent;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentEventRepository;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ShipmentRepository} and {@link ShipmentEventRepository} against PostgreSQL with the
 * schema built by the real Flyway migrations. The context starting at all proves the entities
 * validate against the migrated schema ({@code ddl-auto=validate}).
 */
class ShipmentRepositoryIT extends AbstractIntegrationTest {

    @Autowired ShipmentRepository shipmentRepository;
    @Autowired ShipmentEventRepository shipmentEventRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;

    private static Shipment newShipment(long orderId, String customerId) {
        return Shipment.builder().orderId(orderId).customerId(customerId)
                .trackingNumber("TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                .originAddress("Warehouse, 123 Storage St").destinationAddress("Bagdat Cd. 10, Istanbul")
                .carrier("DHL").estimatedDelivery(LocalDateTime.now().plusDays(5)).build();
    }

    @Test
    void flywayAppliedBothMigrations() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        assertThat(rows).extracting(r -> r.get("version")).containsExactly("1", "2");
        assertThat(rows).allSatisfy(r -> assertThat(r.get("success")).isEqualTo(true));
    }

    @Test
    void save_assignsIdentity_andRoundTripsEveryColumn() {
        long orderId = nextOrderId();
        LocalDateTime eta = LocalDateTime.of(2026, 11, 3, 14, 30, 15, 123456000);
        Shipment s = newShipment(orderId, "alice");
        s.setEstimatedDelivery(eta);
        Shipment saved = shipmentRepository.save(s);

        assertThat(saved.getId()).isNotNull();
        Shipment found = shipmentRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getCustomerId()).isEqualTo("alice");
        assertThat(found.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(found.getCarrier()).isEqualTo("DHL");
        assertThat(found.getOriginAddress()).isEqualTo("Warehouse, 123 Storage St");
        // TIMESTAMP keeps microseconds.
        assertThat(found.getEstimatedDelivery()).isEqualTo(eta);
        assertThat(found.getActualDelivery()).isNull();
        assertThat(found.getCreatedAt()).isCloseTo(LocalDateTime.now(), org.assertj.core.api.Assertions.within(1, ChronoUnit.MINUTES));
        // Enum stored by name, as the migration's VARCHAR(30) status expects.
        assertThat(jdbc.queryForObject("SELECT status FROM shipments WHERE id = ?", String.class, saved.getId()))
                .isEqualTo("PENDING");
    }

    @Test
    void finders_byOrderId_trackingNumber_customerId() {
        String customer = "cust-" + UUID.randomUUID();
        Shipment a = shipmentRepository.save(newShipment(nextOrderId(), customer));
        Shipment b = shipmentRepository.save(newShipment(nextOrderId(), customer));
        shipmentRepository.save(newShipment(nextOrderId(), "someone-else"));

        assertThat(shipmentRepository.findByOrderId(a.getOrderId())).get().extracting(Shipment::getId).isEqualTo(a.getId());
        assertThat(shipmentRepository.findByOrderId(-1L)).isEmpty();
        assertThat(shipmentRepository.findByTrackingNumber(b.getTrackingNumber())).get()
                .extracting(Shipment::getId).isEqualTo(b.getId());
        // Exact, case-sensitive match.
        assertThat(shipmentRepository.findByTrackingNumber(b.getTrackingNumber().toLowerCase())).isEmpty();
        assertThat(shipmentRepository.findByCustomerId(customer)).extracting(Shipment::getId)
                .containsExactlyInAnyOrder(a.getId(), b.getId());
        assertThat(shipmentRepository.findByCustomerId("nobody-" + UUID.randomUUID())).isEmpty();
    }

    @Test
    void trackingNumber_isUnique() {
        Shipment first = shipmentRepository.save(newShipment(nextOrderId(), "alice"));
        Shipment clash = newShipment(nextOrderId(), "bob");
        clash.setTrackingNumber(first.getTrackingNumber());

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(clash))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void notNullColumns_areEnforcedByTheSchema() {
        long orderId = nextOrderId();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shipments (order_id, customer_id, tracking_number, origin_address) VALUES (?, 'c', ?, 'o')",
                orderId, "TRACK-" + UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void carrierLongerThanColumn_isRejected() {
        Shipment s = newShipment(nextOrderId(), "alice");
        s.setCarrier("X".repeat(51));

        assertThatThrownBy(() -> shipmentRepository.saveAndFlush(s)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void eventHistory_isCascadedFromShipment_andReturnedOldestFirst() {
        Shipment s = shipmentRepository.save(newShipment(nextOrderId(), "alice"));
        LocalDateTime t0 = LocalDateTime.of(2026, 10, 29, 9, 0);
        // Saved out of chronological order: the finder must sort by event_timestamp, not by id.
        tx.executeWithoutResult(status -> {
            Shipment managed = shipmentRepository.findById(s.getId()).orElseThrow();
            managed.getEvents().add(event(managed, ShipmentStatus.SHIPPED, "Hub", t0.plusHours(2)));
            managed.getEvents().add(event(managed, ShipmentStatus.PROCESSING, null, t0));
            managed.getEvents().add(event(managed, ShipmentStatus.OUT_FOR_DELIVERY, "Kadikoy", t0.plusHours(5)));
            shipmentRepository.save(managed);
        });

        List<ShipmentEvent> history = shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(s.getId());

        assertThat(history).extracting(ShipmentEvent::getNewStatus)
                .containsExactly(ShipmentStatus.PROCESSING, ShipmentStatus.SHIPPED, ShipmentStatus.OUT_FOR_DELIVERY);
        assertThat(history).extracting(ShipmentEvent::getEventTimestamp)
                .containsExactly(t0, t0.plusHours(2), t0.plusHours(5));
        assertThat(history).extracting(ShipmentEvent::getLocation).containsExactly(null, "Hub", "Kadikoy");
        assertThat(history).allSatisfy(e -> assertThat(e.getId()).isNotNull());
    }

    @Test
    void eventHistory_isScopedToItsShipment() {
        Shipment a = shipmentRepository.save(newShipment(nextOrderId(), "alice"));
        Shipment b = shipmentRepository.save(newShipment(nextOrderId(), "bob"));
        shipmentEventRepository.save(event(a, ShipmentStatus.SHIPPED, null, LocalDateTime.now()));
        shipmentEventRepository.save(event(b, ShipmentStatus.PROCESSING, null, LocalDateTime.now()));
        shipmentEventRepository.save(event(b, ShipmentStatus.SHIPPED, null, LocalDateTime.now().plusMinutes(1)));

        assertThat(shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(a.getId())).hasSize(1);
        assertThat(shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(b.getId())).hasSize(2);
        assertThat(shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(-1L)).isEmpty();
    }

    @Test
    void deletingShipment_removesItsHistory() {
        Shipment s = shipmentRepository.save(newShipment(nextOrderId(), "alice"));
        shipmentEventRepository.save(event(s, ShipmentStatus.SHIPPED, null, LocalDateTime.now()));

        tx.executeWithoutResult(status -> shipmentRepository.delete(shipmentRepository.findById(s.getId()).orElseThrow()));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipment_events WHERE shipment_id = ?", Long.class, s.getId()))
                .isZero();
    }

    @Test
    void eventForUnknownShipment_violatesForeignKey() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO shipment_events (shipment_id, new_status) VALUES (?, 'SHIPPED')", Long.MAX_VALUE))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static ShipmentEvent event(Shipment shipment, ShipmentStatus status, String location, LocalDateTime at) {
        return ShipmentEvent.builder().shipment(shipment).newStatus(status).location(location)
                .eventDescription("Status updated to " + status).eventTimestamp(at).build();
    }
}
