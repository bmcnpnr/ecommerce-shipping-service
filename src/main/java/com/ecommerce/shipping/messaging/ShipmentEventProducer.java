package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.event.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class ShipmentEventProducer {
    private static final Logger log = LoggerFactory.getLogger(ShipmentEventProducer.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishShipmentCreated(ShipmentCreatedEvent e) {
        log.info("Publishing shipment.created for orderId={}", e.getOrderId());
        kafkaTemplate.send("shipment.created", e.getOrderId().toString(), e);
    }

    public void publishShipmentUpdated(ShipmentUpdatedEvent e) {
        log.info("Publishing shipment.updated for orderId={}", e.getOrderId());
        kafkaTemplate.send("shipment.updated", e.getOrderId().toString(), e);
    }
}
