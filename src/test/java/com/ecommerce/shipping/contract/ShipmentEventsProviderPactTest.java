package com.ecommerce.shipping.contract;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.ecommerce.shipping.event.PaymentCompletedEvent;
import com.ecommerce.shipping.messaging.PaymentEventConsumer;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import com.ecommerce.shipping.service.ShipmentService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider side of the Kafka contracts for the {@code shipment.*} events.
 *
 * <p>notification-service holds a pact describing the {@code shipment.created} and
 * {@code shipment.updated} records it can consume (copy in
 * {@code src/test/resources/pacts/}, see {@code ecommerce-platform/sync-pacts.sh}).
 * For each interaction this class produces the record the way production does:
 * {@code shipment.created} comes out of the real {@link PaymentEventConsumer} reacting
 * to a {@code payment.completed} event, {@code shipment.updated} out of the real
 * {@link ShipmentService#updateShipmentStatus}; both hand the event to the
 * {@code KafkaTemplate} (mocked here to capture topic, key and payload), and the payload
 * is serialized with the value serializer configured on the production
 * {@link ProducerFactory} (spring-kafka's {@code JsonSerializer}, no type headers).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Provider("shipping-service")
@PactFolder("pacts")
class ShipmentEventsProviderPactTest {

    @Autowired
    private PaymentEventConsumer paymentEventConsumer;

    @Autowired
    private ShipmentService shipmentService;

    @Autowired
    private ProducerFactory<String, Object> producerFactory;

    @MockitoBean
    private ShipmentRepository shipmentRepository;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ───────────────────────── provider states ─────────────────────────
    // State names are part of the contract: consumers reference them verbatim.

    /** No shipment exists for order 7 yet; the next payment.completed for it creates one (id 31). */
    @State("payment 501 for order 7 has completed")
    void noShipmentForOrder7() {
        reset(shipmentRepository);
        reset(kafkaTemplate);
        when(shipmentRepository.findByOrderId(7L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            s.setId(31L);
            return s;
        });
    }

    @State("shipment 31 for order 7 is being shipped")
    void shipment31Processing() {
        reset(shipmentRepository);
        reset(kafkaTemplate);
        when(shipmentRepository.findById(31L)).thenAnswer(inv -> Optional.of(shipment31(ShipmentStatus.PROCESSING)));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ───────────────────────── message producers ─────────────────────────
    // The annotation value must equal the consumer's expectsToReceive(...) description.

    @PactVerifyProvider("a shipment.created event")
    MessageAndMetadata shipmentCreated() {
        paymentEventConsumer.handlePaymentCompleted(PaymentCompletedEvent.builder()
                .paymentId(501L).orderId(7L).customerId("customer-42")
                .amount(new BigDecimal("99.98")).transactionId("TXN-1A2B3C4D")
                .completedAt(LocalDateTime.now()).build());
        return capturedRecord("shipment.created");
    }

    @PactVerifyProvider("a shipment.updated event")
    MessageAndMetadata shipmentUpdated() {
        shipmentService.updateShipmentStatus(31L, ShipmentStatus.SHIPPED.name(), "Istanbul hub", "Left the warehouse");
        return capturedRecord("shipment.updated");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    /** The (topic, key, value) the production code handed to KafkaTemplate, serialized exactly as the producer would. */
    private MessageAndMetadata capturedRecord(String expectedTopic) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate).send(eq(expectedTopic), key.capture(), value.capture());
        byte[] bytes = valueSerializer().serialize(expectedTopic, new RecordHeaders(), value.getValue());
        return new MessageAndMetadata(bytes, Map.of(
                "contentType", "application/json",
                "kafka_topic", expectedTopic,
                "kafka_key", key.getValue()));
    }

    /** Instantiates and configures the value serializer class exactly as the Kafka client would from the production ProducerFactory. */
    @SuppressWarnings("unchecked")
    private Serializer<Object> valueSerializer() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();
        Object configured = config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        try {
            Class<?> type = configured instanceof Class<?> c ? c : Class.forName(String.valueOf(configured));
            Serializer<Object> serializer = (Serializer<Object>) type.getDeclaredConstructor().newInstance();
            serializer.configure(config, false);
            return serializer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate configured value serializer " + configured, e);
        }
    }

    private static Shipment shipment31(ShipmentStatus status) {
        return Shipment.builder()
                .id(31L).orderId(7L).customerId("customer-42")
                .trackingNumber("TRACK-1A2B3C4D5E").status(status).carrier("DHL")
                .originAddress("Warehouse, 123 Storage St").destinationAddress("Customer Address")
                .estimatedDelivery(LocalDateTime.now().plusDays(5))
                .events(new ArrayList<>())
                .build();
    }
}
