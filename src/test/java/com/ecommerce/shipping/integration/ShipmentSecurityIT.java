package com.ecommerce.shipping.integration;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Security tests against the full stack (real Postgres via Flyway, real Kafka). {@code alice} is a
 * CUSTOMER, {@code bob} the victim; identity is sent the way the gateway forwards it
 * ({@code X-Username}, {@code X-User-Id}, {@code X-User-Role}).
 *
 * <p>Tests assert the secure behaviour; those the current code fails are {@code @Disabled} with the
 * finding id, after being run enabled to confirm they fail for that reason alone.
 */
@Tag("security")
class ShipmentSecurityIT extends AbstractIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired ShipmentRepository shipmentRepository;
    @Autowired JdbcTemplate jdbc;

    // ---- helpers ------------------------------------------------------------------------------------------

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String user, String role) {
        if (user != null) {
            request.header("X-Username", user).header("X-User-Id", Integer.toString(Math.abs(user.hashCode()) % 100000));
        }
        if (role != null) {
            request.header("X-User-Role", role);
        }
        return request;
    }

    private static MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return as(request, "root", "ADMIN");
    }

    private MvcResult run(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String createJson(long orderId, String customerId, String destination) {
        return """
                {"orderId":%d,"customerId":%s,"originAddress":"Warehouse, 123 Storage St","destinationAddress":%s,"carrier":"UPS"}"""
                .formatted(orderId, JSON.writeValueAsString(customerId), JSON.writeValueAsString(destination));
    }

    private Shipment seed(String customerId, ShipmentStatus status) {
        return shipmentRepository.saveAndFlush(Shipment.builder().orderId(nextOrderId()).customerId(customerId)
                .trackingNumber("TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                .status(status).carrier("DHL").originAddress("Warehouse, 123 Storage St")
                .destinationAddress("Bagdat Cd. 10, Istanbul").estimatedDelivery(java.time.LocalDateTime.now().plusDays(5)).build());
    }

    private String statusOf(long id) {
        return jdbc.queryForObject("select status from shipments where id = ?", String.class, id);
    }

    private long historyRows(long id) {
        return jdbc.queryForObject("select count(*) from shipment_events where shipment_id = ?", Long.class, id);
    }

    private static void assertDenied(MvcResult result, String... mustNotContain) throws Exception {
        assertThat(result.getResponse().getStatus()).as("body: %s", result.getResponse().getContentAsString()).isIn(401, 403, 404);
        for (String secret : mustNotContain) {
            assertThat(result.getResponse().getContentAsString()).doesNotContain(secret);
        }
    }

    private static List<ConsumerRecord<String, String>> recordsWithKey(String topic, String key, Duration window) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "sec-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    private static String paymentJson(long orderId, String customerId, String amount) {
        return """
                {"paymentId":501,"orderId":%d,"customerId":%s,"amount":%s,"transactionId":"TXN-1A2B3C4D","completedAt":[2026,10,29,14,30,15,123456789]}"""
                .formatted(orderId, customerId == null ? "null" : "\"" + customerId + "\"", amount);
    }

    /** A valid payment.completed on the same topic/partition: once its shipment exists, everything sent before it was consumed. */
    private void awaitSentinelProcessed() {
        long sentinel = nextOrderId();
        publishRaw(PAYMENT_COMPLETED, String.valueOf(sentinel), paymentJson(sentinel, "sentinel", "10.00"));
        await().atMost(Duration.ofSeconds(60)).until(() -> shipmentRepository.findByOrderId(sentinel).isPresent());
    }

    // ==== Authorization / IDOR (S-1) ===================================================================================

    @Test
    void owner_canReadOwnShipment_andAdmin_canMoveItAlong_andTrackingNeedsNoHeaders() throws Exception {
        String alice = uniq("alice");
        Shipment shipment = seed(alice, ShipmentStatus.PENDING);

        MvcResult own = run(as(get("/api/v1/shipments/order/{id}", shipment.getOrderId()), alice, "CUSTOMER"));
        MvcResult admin = run(json(as(patch("/api/v1/shipments/{id}/status", shipment.getId()), "root", "ADMIN"), "{\"status\":\"PROCESSING\"}"));
        MvcResult anonymous = run(get("/api/v1/shipments/tracking/{t}", shipment.getTrackingNumber()));

        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(JSON.readTree(own.getResponse().getContentAsString()).get("customerId").asString()).isEqualTo(alice);
        assertThat(admin.getResponse().getStatus()).isEqualTo(200);
        assertThat(statusOf(shipment.getId())).isEqualTo("PROCESSING");
        assertThat(anonymous.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void customer_cannotCreateAShipment() throws Exception {
        long orderId = nextOrderId();

        MvcResult result = run(json(as(post("/api/v1/shipments"), uniq("alice"), "CUSTOMER"), createJson(orderId, "bob", "Elsewhere 1")));

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(shipmentRepository.findByOrderId(orderId)).isEmpty();
    }

    @Test
    void customer_cannotPatchShipmentStatus() throws Exception {
        String alice = uniq("alice");
        Shipment own = seed(alice, ShipmentStatus.PENDING);

        MvcResult result = run(json(as(patch("/api/v1/shipments/{id}/status", own.getId()), alice, "CUSTOMER"), "{\"status\":\"PROCESSING\"}"));

        assertThat(result.getResponse().getStatus()).isIn(401, 403);
        assertThat(statusOf(own.getId())).isEqualTo("PENDING");
        assertThat(historyRows(own.getId())).isZero();
        assertThat(recordsWithKey(SHIPMENT_UPDATED, String.valueOf(own.getOrderId()), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void customer_cannotReadAnotherUsersShipment() throws Exception {
        String bob = uniq("bob");
        Shipment shipment = seed(bob, ShipmentStatus.SHIPPED);

        MvcResult result = run(as(get("/api/v1/shipments/order/{id}", shipment.getOrderId()), uniq("alice"), "CUSTOMER"));

        assertDenied(result, bob, "Bagdat Cd. 10");
    }

    @Test
    void customer_cannotReadAnotherUsersShipmentHistory() throws Exception {
        Shipment shipment = seed(uniq("bob"), ShipmentStatus.PENDING);
        run(json(as(patch("/api/v1/shipments/{id}/status", shipment.getId()), "root", "ADMIN"), "{\"status\":\"PROCESSING\",\"location\":\"Bob's depot\"}"));

        MvcResult result = run(as(get("/api/v1/shipments/{id}/history", shipment.getId()), uniq("alice"), "CUSTOMER"));

        assertDenied(result, "Bob's depot");
    }

    @Test
    void privateEndpoints_withoutIdentityHeaders_areRejected() throws Exception {
        Shipment shipment = seed(uniq("bob"), ShipmentStatus.PENDING);

        assertThat(run(get("/api/v1/shipments/order/{id}", shipment.getOrderId())).getResponse().getStatus()).isEqualTo(401);
        assertThat(run(get("/api/v1/shipments/{id}/history", shipment.getId())).getResponse().getStatus()).isEqualTo(401);
        assertThat(run(json(patch("/api/v1/shipments/{id}/status", shipment.getId()), "{\"status\":\"PROCESSING\"}")).getResponse().getStatus()).isEqualTo(401);
        assertThat(run(json(post("/api/v1/shipments"), createJson(nextOrderId(), "bob", "x"))).getResponse().getStatus()).isEqualTo(401);
    }

    // ==== Public tracking endpoint (S-8) ==================================================================================

    @Test
    void publicTracking_returnsStatusCarrierAndEta() throws Exception {
        Shipment shipment = seed(uniq("bob"), ShipmentStatus.SHIPPED);

        JsonNode body = JSON.readTree(run(get("/api/v1/shipments/tracking/{t}", shipment.getTrackingNumber())).getResponse().getContentAsString());

        assertThat(body.get("status").asString()).isEqualTo("SHIPPED");
        assertThat(body.get("carrier").asString()).isEqualTo("DHL");
        assertThat(body.get("trackingNumber").asString()).isEqualTo(shipment.getTrackingNumber());
        assertThat(body.get("estimatedDelivery").asString()).startsWith(String.valueOf(java.time.LocalDate.now().plusDays(5).getYear()));
    }

    @Test
    void publicTracking_doesNotDiscloseCustomerOrAddresses() throws Exception {
        String bob = uniq("bob");
        Shipment shipment = seed(bob, ShipmentStatus.SHIPPED);

        String body = run(get("/api/v1/shipments/tracking/{t}", shipment.getTrackingNumber())).getResponse().getContentAsString();
        JsonNode json = JSON.readTree(body);

        assertThat(body).doesNotContain(bob).doesNotContain("Bagdat Cd. 10").doesNotContain("Warehouse, 123 Storage St");
        assertThat(json.has("customerId")).isFalse();
        assertThat(json.has("orderId")).isFalse();
        assertThat(json.has("originAddress")).isFalse();
        assertThat(json.has("destinationAddress")).isFalse();
    }

    // ==== Status transitions ========================================================================================================

    @Test
    void skippingStates_isRejected_withNoHistoryAndNoEvent() throws Exception {
        Shipment shipment = seed(uniq("carol"), ShipmentStatus.PENDING);

        MvcResult result = run(json(as(patch("/api/v1/shipments/{id}/status", shipment.getId()), "root", "ADMIN"), "{\"status\":\"DELIVERED\"}"));

        assertThat(result.getResponse().getStatus()).isEqualTo(409);
        assertThat(statusOf(shipment.getId())).isEqualTo("PENDING");
        assertThat(historyRows(shipment.getId())).isZero();
        assertThat(recordsWithKey(SHIPMENT_UPDATED, String.valueOf(shipment.getOrderId()), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void deliveredShipment_isImmutable_andUnknownStatuses_changeNothing() throws Exception {
        Shipment delivered = seed(uniq("carol"), ShipmentStatus.DELIVERED);
        Shipment pending = seed(uniq("carol"), ShipmentStatus.PENDING);

        MvcResult backwards = run(json(admin(patch("/api/v1/shipments/{id}/status", delivered.getId())), "{\"status\":\"PENDING\"}"));
        assertThat(backwards.getResponse().getStatus()).isEqualTo(409);
        assertThat(statusOf(delivered.getId())).isEqualTo("DELIVERED");

        for (String status : new String[]{"delivered", "DELIVERED'; DROP TABLE shipments;--", "<script>alert(1)</script>", "ALL", ""}) {
            MvcResult result = run(json(admin(patch("/api/v1/shipments/{id}/status", pending.getId())), "{\"status\":" + JSON.writeValueAsString(status) + "}"));
            assertThat(result.getResponse().getStatus()).as(status).isEqualTo(400);
        }
        assertThat(statusOf(pending.getId())).isEqualTo("PENDING");
        assertThat(historyRows(pending.getId()) + historyRows(delivered.getId())).isZero();
        assertThat(recordsWithKey(SHIPMENT_UPDATED, String.valueOf(pending.getOrderId()), Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void valuesLongerThanTheirColumns_are4xx_andChangeNothing() throws Exception {
        Shipment shipment = seed(uniq("carol"), ShipmentStatus.PENDING);

        MvcResult location = run(json(admin(patch("/api/v1/shipments/{id}/status", shipment.getId())),
                "{\"status\":\"PROCESSING\",\"location\":\"" + "L".repeat(300) + "\"}"));
        MvcResult carrier = run(json(admin(post("/api/v1/shipments")), """
                {"orderId":%d,"customerId":"x","originAddress":"A","destinationAddress":"B","carrier":"%s"}""".formatted(nextOrderId(), "C".repeat(60))));

        assertThat(location.getResponse().getStatus()).as(location.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(carrier.getResponse().getStatus()).as(carrier.getResponse().getContentAsString()).isBetween(400, 499);
        assertThat(statusOf(shipment.getId())).isEqualTo("PENDING");
    }

    // ==== Mass assignment, duplicates ====================================================================================================

    @Test
    void create_serverControlledFieldsInTheBody_areIgnored() throws Exception {
        long orderId = nextOrderId();

        MvcResult result = run(json(admin(post("/api/v1/shipments")), """
                {"orderId":%d,"customerId":"alice","originAddress":"A","destinationAddress":"B",
                 "id":424242,"status":"DELIVERED","trackingNumber":"TRACK-FORGED-1","actualDelivery":"1999-01-01T00:00:00",
                 "createdAt":"1999-01-01T00:00:00","estimatedDelivery":"1999-01-01T00:00:00"}""".formatted(orderId)));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = JSON.readTree(result.getResponse().getContentAsString());
        assertThat(created.get("id").asLong()).isNotEqualTo(424242L);
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("trackingNumber").asString()).isNotEqualTo("TRACK-FORGED-1").matches("TRACK-[0-9A-F]{8}-[0-9A-F]{8}");
        assertThat(created.get("actualDelivery").isNull()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select status, tracking_number, actual_delivery, created_at, estimated_delivery from shipments where order_id = ?", orderId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("actual_delivery")).isNull();
        assertThat(row.get("created_at").toString()).doesNotStartWith("1999");
        assertThat(row.get("estimated_delivery").toString()).doesNotStartWith("1999");
    }

    @Test
    void statusUpdate_otherFieldsInTheBody_areIgnored() throws Exception {
        Shipment shipment = seed(uniq("carol"), ShipmentStatus.PENDING);

        MvcResult result = run(json(admin(patch("/api/v1/shipments/{id}/status", shipment.getId())), """
                {"status":"PROCESSING","trackingNumber":"TRACK-FORGED-1","customerId":"mallory","orderId":1,"destinationAddress":"Mallory's house","carrier":"EVIL"}"""));

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> row = jdbc.queryForMap("select tracking_number, customer_id, order_id, destination_address, carrier from shipments where id = ?", shipment.getId());
        assertThat(row.get("tracking_number")).isEqualTo(shipment.getTrackingNumber());
        assertThat(row.get("customer_id")).isEqualTo(shipment.getCustomerId());
        assertThat(row.get("order_id")).isEqualTo(shipment.getOrderId());
        assertThat(row.get("destination_address")).isEqualTo(shipment.getDestinationAddress());
        assertThat(row.get("carrier")).isEqualTo("DHL");
    }

    @Test
    void secondShipmentForTheSameOrder_isRejected_andLeavesTheFirstUntouched() throws Exception {
        long orderId = nextOrderId();
        assertThat(run(json(admin(post("/api/v1/shipments")), createJson(orderId, "alice", "Home 1"))).getResponse().getStatus()).isEqualTo(201);

        MvcResult second = run(json(admin(post("/api/v1/shipments")), createJson(orderId, "mallory", "Mallory's house")));

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForList("select customer_id from shipments where order_id = ?", String.class, orderId)).containsExactly("alice");
    }

    @Test
    void database_refusesTwoShipmentsForOneOrder() {
        Shipment first = seed(uniq("carol"), ShipmentStatus.PENDING);

        assertThatThrownBy(() -> jdbc.update("""
                insert into shipments (order_id, customer_id, tracking_number, status, origin_address, destination_address)
                values (?, 'carol', ?, 'PENDING', 'A', 'B')""", first.getOrderId(), "TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase()))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForObject("select count(*) from shipments where order_id = ?", Long.class, first.getOrderId())).isEqualTo(1);
    }

    // ==== Injection & data ===========================================================================================================

    private static final String[] SQLI = {
            "' OR '1'='1", "' OR 1=1 --", "TRACK-' --", "'; DROP TABLE shipments; --", "\" OR \"\"=\"", "%", "TRACK-%", "_",
            "' UNION SELECT NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL --"
    };

    @Test
    void sqlInjectionInTrackingNumber_findsNothing_andLeavesTablesIntact() throws Exception {
        seed(uniq("bob"), ShipmentStatus.SHIPPED);
        long before = jdbc.queryForObject("select count(*) from shipments", Long.class);

        for (String payload : SQLI) {
            MvcResult result = run(get("/api/v1/shipments/tracking/{t}", payload));
            assertThat(result.getResponse().getStatus()).as(payload).isEqualTo(404);
            assertThat(result.getResponse().getContentAsString()).as(payload).doesNotContain("customerId");
        }

        assertThat(jdbc.queryForObject("select count(*) from shipments", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select to_regclass('public.shipments') is not null and to_regclass('public.shipment_events') is not null", Boolean.class)).isTrue();
    }

    @Test
    void lowerCasedOrTruncatedTrackingNumber_doesNotMatch() throws Exception {
        Shipment shipment = seed(uniq("bob"), ShipmentStatus.SHIPPED);

        for (String variant : new String[]{shipment.getTrackingNumber().toLowerCase(), shipment.getTrackingNumber().substring(0, 12),
                shipment.getTrackingNumber() + " ", " " + shipment.getTrackingNumber()}) {
            assertThat(run(get("/api/v1/shipments/tracking/{t}", variant)).getResponse().getStatus()).as("'%s'", variant).isEqualTo(404);
        }
    }

    @Test
    void sqlInjectionAndXss_inAddressesLocationAndDescription_areStoredAsPlainData_andReturnedAsJson() throws Exception {
        List<String> payloads = new ArrayList<>(List.of(SQLI));
        payloads.add("<script>alert(1)</script>");
        payloads.add("\"><img src=x onerror=alert(document.cookie)>");
        payloads.add("</script><svg/onload=alert(1)>");
        long before = jdbc.queryForObject("select count(*) from shipments", Long.class);

        for (String raw : payloads) {
            String payload = raw + " " + uniq("u");
            long orderId = nextOrderId();
            MvcResult created = run(json(admin(post("/api/v1/shipments")), createJson(orderId, payload, payload)));
            assertThat(created.getResponse().getStatus()).as(payload).isEqualTo(201);
            JsonNode shipment = JSON.readTree(created.getResponse().getContentAsString());
            assertThat(created.getResponse().getContentType()).startsWith("application/json");
            assertThat(shipment.get("destinationAddress").asString()).isEqualTo(payload);
            assertThat(jdbc.queryForObject("select destination_address from shipments where order_id = ?", String.class, orderId)).isEqualTo(payload);

            String patchBody = "{\"status\":\"PROCESSING\",\"location\":%s,\"description\":%s}".formatted(JSON.writeValueAsString(payload), JSON.writeValueAsString(payload));
            assertThat(run(json(admin(patch("/api/v1/shipments/{id}/status", shipment.get("id").asLong())), patchBody)).getResponse().getStatus()).isEqualTo(200);

            MvcResult history = run(admin(get("/api/v1/shipments/{id}/history", shipment.get("id").asLong())));
            assertThat(history.getResponse().getContentType()).startsWith("application/json");
            JsonNode first = JSON.readTree(history.getResponse().getContentAsString()).get(0);
            assertThat(first.get("location").asString()).isEqualTo(payload);
            assertThat(first.get("eventDescription").asString()).isEqualTo(payload);

            MvcResult tracking = run(get("/api/v1/shipments/tracking/{t}", shipment.get("trackingNumber").asString()));
            assertThat(tracking.getResponse().getContentType()).startsWith("application/json");
        }

        assertThat(jdbc.queryForObject("select count(*) from shipments", Long.class)).isEqualTo(before + payloads.size());
        assertThat(jdbc.queryForObject("select to_regclass('public.shipments') is not null", Boolean.class)).isTrue();
    }

    @Test
    void trackingLookup_withANulByte_isNotAServerError() throws Exception {
        MvcResult result = run(get(URI.create("/api/v1/shipments/tracking/TRACK-%00ABC")));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isLessThan(500);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/shipments/order/-1", "/api/v1/shipments/order/0", "/api/v1/shipments/order/99999999999999999999",
            "/api/v1/shipments/order/1%20OR%201=1", "/api/v1/shipments/%27/history", "/api/v1/shipments/0/history",
            "/api/v1/shipments/tracking/..%2F..%2Factuator", "/api/v1/shipments/tracking/%27"})
    void hostilePathProbes_neverProduceAServerError(String path) throws Exception {
        MvcResult result = run(get(URI.create(path)));

        assertThat(result.getResponse().getStatus()).as("%s -> %s", path, result.getResponse().getContentAsString()).isLessThan(500);
    }

    @Test
    void errorResponses_areJson_withoutStackTracesOrSql() throws Exception {
        Shipment delivered = seed(uniq("carol"), ShipmentStatus.DELIVERED);
        List<MvcResult> errors = List.of(
                run(admin(get("/api/v1/shipments/order/987654321"))),
                run(get("/api/v1/shipments/tracking/TRACK-UNKNOWN")),
                run(admin(get("/api/v1/shipments/987654321/history"))),
                run(json(admin(patch("/api/v1/shipments/{id}/status", delivered.getId())), "{\"status\":\"PENDING\"}")),
                run(json(admin(patch("/api/v1/shipments/{id}/status", delivered.getId())), "{\"status\":\"NOPE\"}")),
                run(json(admin(post("/api/v1/shipments")), "{}")),
                run(json(admin(post("/api/v1/shipments")), "{not json")),
                run(post("/api/v1/shipments/tracking/x")));

        for (MvcResult error : errors) {
            String body = error.getResponse().getContentAsString();
            assertThat(error.getResponse().getStatus()).as(body).isBetween(400, 499);
            assertThat(error.getResponse().getContentType()).startsWith("application/json");
            assertThat(body).doesNotContain("\tat ", "org.springframework", "org.hibernate", "org.postgresql", "select ", "SQLState", "constraint");
        }
    }

    // ==== Forged / malformed Kafka events ===============================================================================================

    @Test
    void forgedPaymentCompleted_withNoRealAmount_createsNoShipment() {
        long zero = nextOrderId();
        long negative = nextOrderId();
        long missing = nextOrderId();

        publishRaw(PAYMENT_COMPLETED, String.valueOf(zero), paymentJson(zero, "mallory", "0"));
        publishRaw(PAYMENT_COMPLETED, String.valueOf(negative), paymentJson(negative, "mallory", "-5.00"));
        publishRaw(PAYMENT_COMPLETED, String.valueOf(missing), paymentJson(missing, "mallory", "null"));
        awaitSentinelProcessed();

        assertThat(shipmentRepository.findByOrderId(zero)).isEmpty();
        assertThat(shipmentRepository.findByOrderId(negative)).isEmpty();
        assertThat(shipmentRepository.findByOrderId(missing)).isEmpty();
    }

    @Test
    void replayedOrForgedPaymentCompleted_forAnOrderWithAShipment_changesNothingAndEmitsNothing() {
        String alice = uniq("alice");
        long orderId = nextOrderId();
        publishRaw(PAYMENT_COMPLETED, String.valueOf(orderId), paymentJson(orderId, alice, "20.00"));
        await().atMost(Duration.ofSeconds(30)).until(() -> shipmentRepository.findByOrderId(orderId).isPresent());
        String tracking = shipmentRepository.findByOrderId(orderId).orElseThrow().getTrackingNumber();

        publishRaw(PAYMENT_COMPLETED, String.valueOf(orderId), paymentJson(orderId, alice, "20.00"));
        publishRaw(PAYMENT_COMPLETED, String.valueOf(orderId), paymentJson(orderId, "mallory", "20.00"));
        awaitSentinelProcessed();

        assertThat(jdbc.queryForList("select customer_id from shipments where order_id = ?", String.class, orderId)).containsExactly(alice);
        assertThat(shipmentRepository.findByOrderId(orderId).orElseThrow().getTrackingNumber()).isEqualTo(tracking);
        assertThat(recordsWithKey(SHIPMENT_CREATED, String.valueOf(orderId), Duration.ofSeconds(3))).hasSize(1);
    }

    @Test
    void malformedOversizedAndUnknownEvents_createNothing_andNeverBlockTheConsumer() {
        long nullCustomer = nextOrderId();
        String attacker = uniq("mallory");
        String huge = "x".repeat(900_000);
        long oversized = nextOrderId();
        List<String> poison = List.of(
                "not json at all",
                "",
                "[1,2,3]",
                "\"just a string\"",
                "{}",
                "{\"orderId\":null,\"customerId\":\"" + attacker + "\",\"amount\":5}",
                "{\"orderId\":\"abc\",\"customerId\":\"" + attacker + "\"}",
                "{\"orderId\":99999999999999999999999,\"customerId\":\"" + attacker + "\"}",
                "{\"type\":\"SOMETHING_ELSE\",\"orderId\":1,\"unknown\":{\"nested\":[1,2,3]}}",
                paymentJson(nullCustomer, null, "20.00"),
                paymentJson(oversized, huge, "20.00"));
        for (String payload : poison) {
            publishRaw(PAYMENT_COMPLETED, "poison", payload);
        }

        awaitSentinelProcessed();

        assertThat(shipmentRepository.findByOrderId(nullCustomer)).isEmpty();
        assertThat(shipmentRepository.findByOrderId(oversized)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from shipments where customer_id = ? or length(customer_id) > 255", Long.class, attacker)).isZero();

        // the group's committed offset caught up with the log end: nothing is being retried in a loop
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            TopicPartition partition = new TopicPartition(PAYMENT_COMPLETED, 0);
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() -> {
                Map<TopicPartition, OffsetAndMetadata> committed = admin.listConsumerGroupOffsets("shipping-service").partitionsToOffsetAndMetadata().get();
                long end = admin.listOffsets(Map.of(partition, OffsetSpec.latest())).all().get().get(partition).offset();
                assertThat(committed.get(partition).offset()).as("committed offset").isEqualTo(end);
            });
        }
    }
}
