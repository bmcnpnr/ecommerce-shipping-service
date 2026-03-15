package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.event.PaymentCompletedEvent;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Component @RequiredArgsConstructor
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventProducer shipmentEventProducer;

    @KafkaListener(topics = "payment.completed", groupId = "shipping-service")
    @Transactional
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        log.info("Received payment.completed for orderId={}", event.getOrderId());

        if (shipmentRepository.findByOrderId(event.getOrderId()).isPresent()) {
            log.warn("Shipment already exists for orderId={}", event.getOrderId());
            return;
        }

        String trackingNumber = "TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        Shipment shipment = Shipment.builder()
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .trackingNumber(trackingNumber)
                .originAddress("Warehouse, 123 Storage St")
                .destinationAddress("Customer Address")
                .carrier("DHL")
                .estimatedDelivery(LocalDateTime.now().plusDays(5))
                .build();

        shipmentRepository.save(shipment);
        log.info("Shipment created for orderId={}, tracking={}", event.getOrderId(), trackingNumber);

        shipmentEventProducer.publishShipmentCreated(
            com.ecommerce.shipping.event.ShipmentCreatedEvent.builder()
                .shipmentId(shipment.getId()).orderId(shipment.getOrderId())
                .customerId(shipment.getCustomerId()).trackingNumber(trackingNumber)
                .carrier(shipment.getCarrier()).estimatedDelivery(shipment.getEstimatedDelivery())
                .build());
    }
}
