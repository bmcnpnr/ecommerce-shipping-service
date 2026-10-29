package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

/**
 * payment.completed on a real broker → shipment row in PostgreSQL → shipment.created on the wire.
 *
 * <p>The inbound record is payment-service's {@code PaymentCompletedEvent} exactly as it
 * serializes it (and as the payment pact pins it): raw JSON, no type headers, record key =
 * orderId, {@code completedAt} as a Jackson-timestamp integer array.
 */
class PaymentCompletedFlowIT extends AbstractIntegrationTest {

    @Autowired ShipmentRepository shipmentRepository;

    private static String paymentCompleted(long orderId, String customerId) {
        return """
                {"paymentId":501,"orderId":%d,"customerId":"%s","amount":99.98,\
                "transactionId":"TXN-1A2B3C4D","completedAt":[2026,10,29,14,30,15,123456789]}""".formatted(orderId, customerId);
    }

    private Shipment awaitShipment(long orderId) {
        return await().atMost(Duration.ofSeconds(30))
                .until(() -> shipmentRepository.findByOrderId(orderId), Optional::isPresent)
                .orElseThrow();
    }

    @Test
    void paymentCompleted_createsShipment_andPublishesShipmentCreated() {
        long orderId = nextOrderId();
        LocalDateTime before = LocalDateTime.now();

        publishRaw(PAYMENT_COMPLETED, String.valueOf(orderId), paymentCompleted(orderId, "customer-42"));

        Shipment shipment = awaitShipment(orderId);
        assertThat(shipment.getCustomerId()).isEqualTo("customer-42");
        assertThat(shipment.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(shipment.getCarrier()).isEqualTo("DHL");
        assertThat(shipment.getTrackingNumber()).matches("TRACK-[0-9A-F]{8}-[0-9A-F]{8}");
        assertThat(shipment.getEstimatedDelivery()).isAfterOrEqualTo(before.plusDays(5).minusSeconds(1));

        List<Received> created = awaitRecords(SHIPMENT_CREATED, String.valueOf(orderId), r -> !r.isEmpty());
        assertThat(created).hasSize(1);
        JsonNode event = created.get(0).value();
        assertThat(event.get("shipmentId").asLong()).isEqualTo(shipment.getId());
        assertThat(event.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(event.get("customerId").asString()).isEqualTo("customer-42");
        assertThat(event.get("trackingNumber").asString()).isEqualTo(shipment.getTrackingNumber());
        assertThat(event.get("carrier").asString()).isEqualTo("DHL");
        assertThat(event.propertyNames()).containsExactlyInAnyOrder(
                "shipmentId", "orderId", "customerId", "trackingNumber", "carrier", "estimatedDelivery");

        // estimatedDelivery is a LocalDateTime on the wire: an int array [y, M, d, H, m(, s(, nanos))],
        // NOT a LocalDate [y, M, d] and not an ISO string (see the notification-service poison pill).
        JsonNode eta = event.get("estimatedDelivery");
        assertThat(eta.isArray()).as("estimatedDelivery as array: %s", eta).isTrue();
        assertThat(eta.size()).isBetween(5, 7);
        LocalDateTime etaOnWire = LocalDateTime.of(eta.get(0).asInt(), eta.get(1).asInt(), eta.get(2).asInt(),
                eta.get(3).asInt(), eta.get(4).asInt(),
                eta.size() > 5 ? eta.get(5).asInt() : 0, eta.size() > 6 ? eta.get(6).asInt() : 0);
        // Postgres TIMESTAMP rounds to microseconds, the event carries the in-memory nanos.
        assertThat(etaOnWire).isCloseTo(shipment.getEstimatedDelivery(), within(1, ChronoUnit.MICROS));
    }

    @Test
    void redeliveredPaymentCompleted_isIdempotent() {
        long orderId = nextOrderId();
        String key = String.valueOf(orderId);

        publishRaw(PAYMENT_COMPLETED, key, paymentCompleted(orderId, "customer-7"));
        Shipment first = awaitShipment(orderId);
        awaitRecords(SHIPMENT_CREATED, key, r -> !r.isEmpty());

        publishRaw(PAYMENT_COMPLETED, key, paymentCompleted(orderId, "customer-7"));
        // Barrier: a later order published after the duplicate is processed in partition order,
        // so once it exists the duplicate has been consumed too.
        long barrier = nextOrderId();
        publishRaw(PAYMENT_COMPLETED, String.valueOf(barrier), paymentCompleted(barrier, "customer-7"));
        awaitShipment(barrier);

        assertThat(shipmentRepository.findByCustomerId("customer-7"))
                .filteredOn(s -> s.getOrderId() == orderId)
                .singleElement().extracting(Shipment::getId).isEqualTo(first.getId());
        // Single partition: everything up to the barrier's shipment.created is in the result.
        String barrierKey = String.valueOf(barrier);
        List<Received> all = awaitRecords(SHIPMENT_CREATED, null,
                r -> r.stream().anyMatch(x -> barrierKey.equals(x.key())));
        assertThat(all).filteredOn(r -> key.equals(r.key())).hasSize(1);
    }
}
