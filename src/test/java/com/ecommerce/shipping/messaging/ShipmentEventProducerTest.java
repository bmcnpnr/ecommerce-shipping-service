package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.event.ShipmentCreatedEvent;
import com.ecommerce.shipping.event.ShipmentUpdatedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class ShipmentEventProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @InjectMocks ShipmentEventProducer producer;

    @Test
    void publishShipmentCreated_sendsToShipmentCreated_keyedByOrderId() {
        ShipmentCreatedEvent event = ShipmentCreatedEvent.builder().shipmentId(31L).orderId(7L).customerId("alice")
                .trackingNumber("TRACK-1").carrier("DHL").estimatedDelivery(LocalDateTime.now()).build();

        producer.publishShipmentCreated(event);

        verify(kafkaTemplate).send("shipment.created", "7", event);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void publishShipmentUpdated_sendsToShipmentUpdated_keyedByOrderId() {
        ShipmentUpdatedEvent event = ShipmentUpdatedEvent.builder().shipmentId(31L).orderId(8L)
                .trackingNumber("TRACK-1").newStatus("SHIPPED").location("Hub").updatedAt(LocalDateTime.now()).build();

        producer.publishShipmentUpdated(event);

        verify(kafkaTemplate).send("shipment.updated", "8", event);
        verifyNoMoreInteractions(kafkaTemplate);
    }
}
