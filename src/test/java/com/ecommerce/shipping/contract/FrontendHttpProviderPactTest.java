package com.ecommerce.shipping.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Consumer;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Provider side of the HTTP contract the frontend (browser -> api-gateway -> shipping-service) holds with
 * shipping-service. The pact (copy in {@code src/test/resources/pacts/}) is replayed against the real application
 * (controller, service, JPA on H2) on a random port; state data is inserted with {@link JdbcTemplate}.
 *
 * <p>Only the {@code ecommerce-frontend} consumer is verified here; the Kafka pact of notification-service
 * lives in the same folder and is verified by {@link ShipmentEventsProviderPactTest}.
 *
 * <p>The pact requests carry {@code Authorization: Bearer <jwt>} but not the {@code X-User-*} headers the gateway
 * injects after validating the token, so {@link #addGatewayIdentityHeaders} reproduces that step. Public paths
 * (tracking) carry no token and get no headers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Provider("shipping-service")
@PactFolder("pacts")
@Consumer("ecommerce-frontend")
class FrontendHttpProviderPactTest {

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context, HttpRequest request) {
        addGatewayIdentityHeaders(request);
        context.verifyInteraction();
    }

    /** What api-gateway's JwtAuthenticationFilter does for an authenticated route (no signature check here). */
    static void addGatewayIdentityHeaders(HttpRequest request) {
        Header auth = request.getFirstHeader("Authorization");
        if (auth == null || !auth.getValue().startsWith("Bearer ")) return;
        String payload = auth.getValue().substring("Bearer ".length()).split("[.]")[1];
        JsonNode claims = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(payload));
        request.setHeader("X-Username", claims.get("sub").asString());
        request.setHeader("X-User-Id", claims.get("userId").asString());
        request.setHeader("X-User-Role", claims.get("role").asString());
    }

    // ───────────────────────── provider states ─────────────────────────
    // State names are part of the contract: consumers reference them verbatim.

    @State("shipment 31 for order 7 is being shipped")
    void shipment31ForOrder7() {
        clearShipments();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO shipments (id, order_id, customer_id, tracking_number, status, carrier, origin_address, "
                        + "destination_address, estimated_delivery, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                31L, 7L, "alice", "TRACK-1A2B3C4D5E", "SHIPPED", "DHL", "Warehouse, 123 Storage St",
                "1 Example Street, Istanbul", now.plusDays(3), now.minusDays(1), now);
        jdbc.update("INSERT INTO shipment_events (shipment_id, event_description, new_status, location, event_timestamp) "
                        + "VALUES (?,?,?,?,?)",
                31L, "Shipment created", "PENDING", "Istanbul hub", now.minusDays(1));
        jdbc.update("INSERT INTO shipment_events (shipment_id, event_description, new_status, location, event_timestamp) "
                        + "VALUES (?,?,?,?,?)",
                31L, "Left the warehouse", "SHIPPED", "Istanbul hub", now.minusHours(2));
    }

    @State("no shipment has tracking number TRACK-UNKNOWN")
    void noShipmentTrackUnknown() {
        clearShipments();
    }

    private void clearShipments() {
        jdbc.update("DELETE FROM shipment_events");
        jdbc.update("DELETE FROM shipments");
    }
}
