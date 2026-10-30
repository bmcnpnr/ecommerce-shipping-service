package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.config.KafkaProducerConfig;
import com.ecommerce.shipping.event.ShipmentCreatedEvent;
import com.ecommerce.shipping.event.ShipmentUpdatedEvent;
import com.ecommerce.shipping.outbox.OutboxEvent;
import com.ecommerce.shipping.outbox.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ShipmentEventProducerTest {

    @Mock OutboxEventRepository outbox;
    @InjectMocks ShipmentEventProducer producer;

    private OutboxEvent stored() {
        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(row.capture());
        verifyNoMoreInteractions(outbox);
        return row.getValue();
    }

    @Test
    void publishShipmentCreated_storesAnOutboxRow_forShipmentCreated_keyedByOrderId() {
        ShipmentCreatedEvent event = ShipmentCreatedEvent.builder().shipmentId(31L).orderId(7L).customerId("alice")
                .trackingNumber("TRACK-1").carrier("DHL").estimatedDelivery(LocalDateTime.of(2026, 11, 3, 14, 30, 15, 123456789)).build();

        producer.publishShipmentCreated(event);

        OutboxEvent row = stored();
        assertThat(row.getTopic()).isEqualTo("shipment.created");
        assertThat(row.getEventKey()).isEqualTo("7");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getParkedAt()).isNull();
        JsonNode json = JsonMapper.builder().build().readTree(row.getPayload());
        assertThat(json.get("shipmentId").asLong()).isEqualTo(31L);
        assertThat(json.get("customerId").asString()).isEqualTo("alice");
        // java.time stays an integer array on the wire: that is what every consumer and the pacts pin
        assertThat(json.get("estimatedDelivery").isArray()).isTrue();
        assertThat(json.get("estimatedDelivery").toString()).isEqualTo("[2026,11,3,14,30,15,123456789]");
    }

    @Test
    void publishShipmentUpdated_storesAnOutboxRow_forShipmentUpdated_keyedByOrderId() {
        ShipmentUpdatedEvent event = ShipmentUpdatedEvent.builder().shipmentId(31L).orderId(8L)
                .trackingNumber("TRACK-1").newStatus("SHIPPED").location("Hub").updatedAt(LocalDateTime.now()).build();

        producer.publishShipmentUpdated(event);

        OutboxEvent row = stored();
        assertThat(row.getTopic()).isEqualTo("shipment.updated");
        assertThat(row.getEventKey()).isEqualTo("8");
        assertThat(row.getPayload()).contains("\"newStatus\":\"SHIPPED\"").contains("\"location\":\"Hub\"");
    }

    @Test
    void thePayloadIsByteForByteWhatTheKafkaJsonSerializerAlwaysSent() {
        ShipmentUpdatedEvent event = ShipmentUpdatedEvent.builder().shipmentId(31L).orderId(8L)
                .trackingNumber("TRACK-1").newStatus("SHIPPED").location(null).updatedAt(LocalDateTime.of(2026, 10, 30, 9, 12, 44, 512000000)).build();
        byte[] before = new JacksonJsonSerializer<ShipmentUpdatedEvent>(KafkaProducerConfig.eventJsonMapper()).noTypeInfo()
                .serialize("shipment.updated", event);

        producer.publishShipmentUpdated(event);

        // the relay sends the stored text through a StringSerializer, i.e. as UTF-8
        assertThat(stored().getPayload().getBytes(StandardCharsets.UTF_8)).isEqualTo(before);
    }
}
