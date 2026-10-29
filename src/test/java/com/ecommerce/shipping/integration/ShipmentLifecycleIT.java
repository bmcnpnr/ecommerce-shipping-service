package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The status lifecycle over HTTP against the real stack: each PATCH must update the
 * {@code shipments} row, append a {@code shipment_events} row and publish {@code shipment.updated}.
 */
class ShipmentLifecycleIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbc;

    private long createShipment(long orderId) throws Exception {
        String body = mockMvc.perform(admin(post("/api/v1/shipments")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":%d,"customerId":"alice","originAddress":"Warehouse, 123 Storage St",\
                        "destinationAddress":"Bagdat Cd. 10, Istanbul","carrier":"UPS"}""".formatted(orderId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return JSON.readTree(body).get("id").asLong();
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request.header("X-Username", "root").header("X-User-Role", "ADMIN");
    }

    private ResultActions patchStatus(long id, String json) throws Exception {
        return mockMvc.perform(admin(patch("/api/v1/shipments/{id}/status", id)).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static LocalDateTime fromIntArray(JsonNode a) {
        assertThat(a.isArray()).as("LocalDateTime as int array: %s", a).isTrue();
        assertThat(a.size()).isBetween(5, 7);
        return LocalDateTime.of(a.get(0).asInt(), a.get(1).asInt(), a.get(2).asInt(), a.get(3).asInt(), a.get(4).asInt(),
                a.size() > 5 ? a.get(5).asInt() : 0, a.size() > 6 ? a.get(6).asInt() : 0);
    }

    private long historyRows(long shipmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM shipment_events WHERE shipment_id = ?", Long.class, shipmentId);
    }

    @Test
    void fullLifecycle_updatesRow_writesHistory_andPublishesShipmentUpdated() throws Exception {
        long orderId = nextOrderId();
        String key = String.valueOf(orderId);
        long id = createShipment(orderId);
        String tracking = shipmentRepository.findById(id).orElseThrow().getTrackingNumber();
        LocalDateTime before = LocalDateTime.now();

        patchStatus(id, "{\"status\":\"PROCESSING\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PROCESSING"));
        patchStatus(id, "{\"status\":\"SHIPPED\",\"location\":\"Istanbul hub\",\"description\":\"Left the warehouse\"}")
                .andExpect(status().isOk());
        patchStatus(id, "{\"status\":\"OUT_FOR_DELIVERY\",\"location\":\"Kadikoy\"}").andExpect(status().isOk());
        patchStatus(id, "{\"status\":\"DELIVERED\",\"location\":\"Door\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.actualDelivery").isNotEmpty());

        // shipments row
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, actual_delivery, updated_at FROM shipments WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DELIVERED");
        assertThat(row.get("actual_delivery")).isNotNull();
        assertThat(row.get("updated_at")).isNotNull();

        // shipment_events rows, through the history endpoint (oldest first)
        mockMvc.perform(admin(get("/api/v1/shipments/{id}/history", id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].newStatus").value("PROCESSING"))
                .andExpect(jsonPath("$[0].eventDescription").value("Status updated to PROCESSING"))
                .andExpect(jsonPath("$[0].shipmentId").value(id))
                .andExpect(jsonPath("$[1].newStatus").value("SHIPPED"))
                .andExpect(jsonPath("$[1].location").value("Istanbul hub"))
                .andExpect(jsonPath("$[1].eventDescription").value("Left the warehouse"))
                .andExpect(jsonPath("$[2].newStatus").value("OUT_FOR_DELIVERY"))
                .andExpect(jsonPath("$[3].newStatus").value("DELIVERED"))
                .andExpect(jsonPath("$[3].location").value("Door"));
        assertThat(historyRows(id)).isEqualTo(4);

        // shipment.updated, one per transition, in order, keyed by orderId
        List<Received> updates = awaitRecords(SHIPMENT_UPDATED, key, r -> r.size() >= 4);
        assertThat(updates).hasSize(4);
        assertThat(updates).extracting(r -> r.value().get("newStatus").asString())
                .containsExactly("PROCESSING", "SHIPPED", "OUT_FOR_DELIVERY", "DELIVERED");
        assertThat(updates).extracting(r -> r.value().get("location").isNull() ? null : r.value().get("location").asString())
                .containsExactly(null, "Istanbul hub", "Kadikoy", "Door");
        for (Received r : updates) {
            JsonNode e = r.value();
            assertThat(e.propertyNames()).containsExactlyInAnyOrder(
                    "shipmentId", "orderId", "trackingNumber", "newStatus", "location", "updatedAt");
            assertThat(e.get("shipmentId").asLong()).isEqualTo(id);
            assertThat(e.get("orderId").asLong()).isEqualTo(orderId);
            assertThat(e.get("trackingNumber").asString()).isEqualTo(tracking);
            assertThat(fromIntArray(e.get("updatedAt"))).isAfterOrEqualTo(before.withNano(0));
        }

        // The manual create path publishes no shipment.created (documented behaviour).
        String barrierKey = String.valueOf(publishBarrierShipmentCreated());
        assertThat(awaitRecords(SHIPMENT_CREATED, null, r -> r.stream().anyMatch(x -> barrierKey.equals(x.key()))))
                .noneMatch(r -> key.equals(r.key()));
    }

    @Test
    void terminalShipment_rejectsFurtherUpdates_withoutSideEffects() throws Exception {
        long orderId = nextOrderId();
        long id = createShipment(orderId);
        patchStatus(id, "{\"status\":\"PROCESSING\"}").andExpect(status().isOk());
        patchStatus(id, "{\"status\":\"SHIPPED\"}").andExpect(status().isOk());
        patchStatus(id, "{\"status\":\"RETURNED\"}").andExpect(status().isOk());

        for (ShipmentStatus next : ShipmentStatus.values()) {
            patchStatus(id, "{\"status\":\"" + next + "\"}")
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Cannot update status of a RETURNED shipment"));
        }

        Shipment after = shipmentRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(ShipmentStatus.RETURNED);
        assertThat(after.getActualDelivery()).isNull();
        assertThat(historyRows(id)).isEqualTo(3);
        // Barrier on the same partition: a later update for another shipment.
        long barrierOrder = nextOrderId();
        patchStatus(createShipment(barrierOrder), "{\"status\":\"PROCESSING\"}").andExpect(status().isOk());
        String barrierKey = String.valueOf(barrierOrder);
        assertThat(awaitRecords(SHIPMENT_UPDATED, null, r -> r.stream().anyMatch(x -> barrierKey.equals(x.key()))))
                .filteredOn(r -> String.valueOf(orderId).equals(r.key()))
                .extracting(r -> r.value().get("newStatus").asString())
                .containsExactly("PROCESSING", "SHIPPED", "RETURNED");
    }

    @Test
    void lookupsAndErrors_overHttp() throws Exception {
        long orderId = nextOrderId();
        long id = createShipment(orderId);
        String tracking = shipmentRepository.findById(id).orElseThrow().getTrackingNumber();

        mockMvc.perform(admin(get("/api/v1/shipments/order/{o}", orderId))).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.carrier").value("UPS"));
        mockMvc.perform(get("/api/v1/shipments/tracking/{t}", tracking)).andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value(tracking))
                .andExpect(jsonPath("$.orderId").doesNotExist());
        mockMvc.perform(admin(get("/api/v1/shipments/{id}/history", id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(admin(post("/api/v1/shipments")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":%d,"customerId":"bob","originAddress":"W","destinationAddress":"H"}""".formatted(orderId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Shipment already exists for order: " + orderId));
        mockMvc.perform(admin(get("/api/v1/shipments/order/{o}", -1))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/shipments/tracking/{t}", tracking.toLowerCase())).andExpect(status().isNotFound());
        patchStatus(Long.MAX_VALUE, "{\"status\":\"SHIPPED\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Shipment not found: " + Long.MAX_VALUE));
        mockMvc.perform(admin(get("/api/v1/shipments/{id}/history", Long.MAX_VALUE))).andExpect(status().isNotFound());
    }

    @Test
    void unknownStatus_is400_andChangesNothing() throws Exception {
        long id = createShipment(nextOrderId());

        patchStatus(id, "{\"status\":\"FLYING\"}").andExpect(status().isBadRequest());
        patchStatus(id, "{\"status\":\"shipped\"}").andExpect(status().isBadRequest());

        assertThat(shipmentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(historyRows(id)).isZero();
    }

    /**
     * shipment.updated is sent inside the transaction, before commit. An over-long location
     * still announces nothing. Since S-80 the over-long location is refused by request validation (400)
     * before the service runs; the database never sees it.
     */
    @Test
    void locationOverflow_isRolledBack_andPublishesNoShipmentUpdated() throws Exception {
        long orderId = nextOrderId();
        long id = createShipment(orderId);

        patchStatus(id, "{\"status\":\"PROCESSING\",\"location\":\"" + "x".repeat(300) + "\"}").andExpect(status().isBadRequest());

        assertThat(shipmentRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(historyRows(id)).isZero();
        long barrierOrder = nextOrderId();
        patchStatus(createShipment(barrierOrder), "{\"status\":\"PROCESSING\"}").andExpect(status().isOk());
        String barrierKey = String.valueOf(barrierOrder);
        assertThat(awaitRecords(SHIPMENT_UPDATED, null, r -> r.stream().anyMatch(x -> barrierKey.equals(x.key()))))
                .noneMatch(r -> String.valueOf(orderId).equals(r.key()));
    }

    /** Drives one shipment.created through the Kafka path; returns its orderId (= record key). */
    private long publishBarrierShipmentCreated() {
        long barrier = nextOrderId();
        publishRaw(PAYMENT_COMPLETED, String.valueOf(barrier), """
                {"paymentId":1,"orderId":%d,"customerId":"barrier","amount":1.00,"transactionId":"TXN-B",\
                "completedAt":[2026,10,29,14,30,15]}""".formatted(barrier));
        return barrier;
    }
}
