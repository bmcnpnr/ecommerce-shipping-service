package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.event.PaymentCompletedEvent;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.TrackingNumbers;
import com.ecommerce.shipping.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDateTime;
import java.math.BigDecimal;

@Component @RequiredArgsConstructor
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);
    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventProducer shipmentEventProducer;

    // Deliberately not @Transactional: a duplicate (unique order_id) must be caught here, and a failed flush
    // inside an outer transaction would only surface at commit, after this method, as a retry loop.
    @KafkaListener(topics = "payment.completed", groupId = "shipping-service")
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        if (!isAcceptable(event)) {
            // log and drop: throwing would make the container redeliver the same bad record forever
            log.warn("Ignoring payment.completed without a valid order, customer or positive amount (orderId={})",
                    event == null ? null : event.getOrderId());
            return;
        }
        log.info("Received payment.completed for orderId={}", event.getOrderId());

        if (shipmentRepository.findByOrderId(event.getOrderId()).isPresent()) {
            log.warn("Shipment already exists for orderId={}", event.getOrderId());
            return;
        }

        String trackingNumber = TrackingNumbers.generate();

        Shipment shipment = Shipment.builder()
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .trackingNumber(trackingNumber)
                .originAddress("Warehouse, 123 Storage St")
                .destinationAddress("Customer Address")
                .carrier("DHL")
                .estimatedDelivery(LocalDateTime.now().plusDays(5))
                .build();

        try {
            shipmentRepository.save(shipment);
        } catch (DataIntegrityViolationException e) {
            if (shipmentRepository.findByOrderId(event.getOrderId()).isPresent()) {
                // lost a race with a concurrent delivery of the same event: idempotent, nothing to publish
                log.warn("Shipment already exists for orderId={} (concurrent duplicate)", event.getOrderId());
                return;
            }
            throw e;
        }
        log.info("Shipment created for orderId={}, tracking={}", event.getOrderId(), trackingNumber);

        shipmentEventProducer.publishShipmentCreated(
            com.ecommerce.shipping.event.ShipmentCreatedEvent.builder()
                .shipmentId(shipment.getId()).orderId(shipment.getOrderId())
                .customerId(shipment.getCustomerId()).trackingNumber(trackingNumber)
                .carrier(shipment.getCarrier()).estimatedDelivery(shipment.getEstimatedDelivery())
                .build());
    }

    private static boolean isAcceptable(PaymentCompletedEvent e) {
        if (e == null || e.getOrderId() == null || e.getCustomerId() == null || e.getCustomerId().isBlank()
                || e.getCustomerId().length() > 255 || e.getCustomerId().indexOf(0) >= 0) {
            return false;
        }
        BigDecimal amount = e.getAmount();
        return amount != null && amount.signum() > 0;
    }
}
