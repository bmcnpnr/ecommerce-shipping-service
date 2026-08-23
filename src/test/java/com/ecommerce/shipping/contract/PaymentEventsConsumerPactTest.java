package com.ecommerce.shipping.contract;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.dsl.LambdaDsl;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.ecommerce.shipping.event.ShipmentCreatedEvent;
import com.ecommerce.shipping.messaging.ShipmentEventProducer;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.ecommerce.shipping.contract.KafkaPactDsl.EXAMPLE_TIME;
import static com.ecommerce.shipping.contract.KafkaPactDsl.localDateTime;
import static com.ecommerce.shipping.contract.KafkaPactDsl.timestampArrays;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Consumer side of the Kafka contract between shipping-service and payment-service.
 *
 * <p>shipping-service listens on {@code payment.completed}
 * ({@link com.ecommerce.shipping.messaging.PaymentEventConsumer}), creates the
 * shipment for the paid order and publishes {@code shipment.created}. The pact below
 * is the message shape shipping-service can handle; the test publishes exactly that
 * message to an embedded Kafka broker and lets the <em>production</em> consumer path
 * — {@code KafkaProducerConfig}'s listener container factory, {@code StringDeserializer}
 * + {@code JsonMessageConverter}, the {@code @KafkaListener} method — process it.
 *
 * <p>shipping-service reads {@code orderId} and {@code customerId}, but deserializes the
 * whole record into {@code PaymentCompletedEvent}, so a type change in any field it
 * declares would break it; every declared field is in the contract with a type matcher.
 *
 * <p>Running this class writes {@code target/pacts/shipping-service-payment-service.json},
 * which is copied into payment-service's {@code src/test/resources/pacts/} (see
 * {@code ecommerce-platform/sync-pacts.sh}) and verified there by
 * {@code PaymentEventsProviderPactTest} against the real producer code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"payment.completed", "shipment.created", "shipment.updated"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers")
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = "payment-service", providerType = ProviderType.ASYNCH, pactVersion = PactSpecVersion.V4)
class PaymentEventsConsumerPactTest {

    static final String CONSUMER = "shipping-service";

    @MockitoBean
    private ShipmentRepository shipmentRepository;

    @MockitoBean
    private ShipmentEventProducer shipmentEventProducer;

    @Autowired
    private EmbeddedKafkaBroker broker;

    // ───────────────────────────── pacts ─────────────────────────────

    @Pact(consumer = CONSUMER)
    V4Pact paymentCompleted(MessagePactBuilder builder) {
        return builder
                .given("payment 501 for order 7 has completed")
                .expectsToReceive("a payment.completed event")
                .withMetadata(md -> md
                        .add("contentType", "application/json")
                        .add("kafka_topic", "payment.completed")
                        // record key = orderId
                        .matchRegex("kafka_key", "\\d+", "7"))
                .withContent(timestampArrays(LambdaDsl.newJsonBody(event -> {
                    event.integerType("paymentId", 501)
                            .integerType("orderId", 7)
                            .stringType("customerId", "customer-42")
                            .numberType("amount", 99.98)
                            .stringType("transactionId", "TXN-1A2B3C4D");
                    localDateTime(event, "completedAt", EXAMPLE_TIME);
                }).build(), "completedAt"))
                .toPact(V4Pact.class);
    }

    // ───────────────────────────── tests ─────────────────────────────

    @Test
    @PactTestFor(pactMethod = "paymentCompleted")
    void paymentCompleted_createsTheShipmentAndAnnouncesIt(List<V4Interaction.AsynchronousMessage> messages) {
        when(shipmentRepository.findByOrderId(7L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            s.setId(31L);
            return s;
        });

        publish(messages.get(0));

        ArgumentCaptor<Shipment> saved = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentRepository, timeout(30_000)).save(saved.capture());
        // Fields shipping-service copies out of the payment.completed record.
        assertThat(saved.getValue().getOrderId()).isEqualTo(7L);
        assertThat(saved.getValue().getCustomerId()).isEqualTo("customer-42");

        ArgumentCaptor<ShipmentCreatedEvent> announced = ArgumentCaptor.forClass(ShipmentCreatedEvent.class);
        verify(shipmentEventProducer, timeout(30_000)).publishShipmentCreated(announced.capture());
        assertThat(announced.getValue().getOrderId()).isEqualTo(7L);
        assertThat(announced.getValue().getCustomerId()).isEqualTo("customer-42");
        assertThat(announced.getValue().getShipmentId()).isEqualTo(31L);
    }

    /** Publishes the pact's example message as raw bytes on the topic named in its metadata — no type headers, exactly like the producers. */
    private void publish(V4Interaction.AsynchronousMessage message) {
        String topic = String.valueOf(message.getMetadata().get("kafka_topic"));
        String key = String.valueOf(message.getMetadata().get("kafka_key"));
        Map<String, Object> props = KafkaTestUtils.producerProps(broker);
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props, new StringSerializer(), new ByteArraySerializer())) {
            producer.send(new ProducerRecord<>(topic, key, message.contentsAsBytes()));
            producer.flush();
        }
    }
}
