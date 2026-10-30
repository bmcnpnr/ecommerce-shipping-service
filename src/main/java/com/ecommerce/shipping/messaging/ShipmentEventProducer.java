package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.config.KafkaProducerConfig;
import com.ecommerce.shipping.event.*;
import com.ecommerce.shipping.outbox.OutboxEvent;
import com.ecommerce.shipping.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

/**
 * Announces shipment events through the transactional outbox: the event is serialised here, with the
 * mapper the Kafka producer always used (java.time as integer arrays, no type headers), and stored in
 * {@code outbox_event} in the caller's transaction. {@code OutboxRelay} publishes it after the commit,
 * so a change and its event succeed or fail together, and a broker that is down never fails or slows
 * the request that caused the event.
 */
@Component @RequiredArgsConstructor
public class ShipmentEventProducer {
    private static final Logger log = LoggerFactory.getLogger(ShipmentEventProducer.class);
    private static final JsonMapper MAPPER = KafkaProducerConfig.eventJsonMapper();

    private final OutboxEventRepository outbox;

    @Transactional
    public void publishShipmentCreated(ShipmentCreatedEvent e) {
        enqueue("shipment.created", e.getOrderId(), e);
    }

    @Transactional
    public void publishShipmentUpdated(ShipmentUpdatedEvent e) {
        enqueue("shipment.updated", e.getOrderId(), e);
    }

    private void enqueue(String topic, Long orderId, Object event) {
        outbox.save(OutboxEvent.builder()
                .topic(topic)
                .eventKey(orderId.toString())
                .payload(MAPPER.writeValueAsString(event))
                .createdAt(LocalDateTime.now())
                .build());
        log.debug("Queued {} for orderId={}", topic, orderId);
    }
}
