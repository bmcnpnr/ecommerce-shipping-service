package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.event.PaymentCompletedEvent;
import com.ecommerce.shipping.event.ShipmentCreatedEvent;
import com.ecommerce.shipping.logging.LogThrottle;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.TrackingNumbers;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Component
public class PaymentEventConsumer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventConsumer.class);

    // Anyone who can reach the broker can write to payment.completed (no Kafka ACLs), so what a bad or
    // repeated event may log is bounded: one line per kind per 10 s, with the number suppressed.
    private final LogThrottle rejectedLog = new LogThrottle(10, TimeUnit.SECONDS);
    private final LogThrottle duplicateLog = new LogThrottle(10, TimeUnit.SECONDS);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventProducer shipmentEventProducer;
    private final TransactionTemplate transactions;

    public PaymentEventConsumer(ShipmentRepository shipmentRepository, ShipmentEventProducer shipmentEventProducer,
                                PlatformTransactionManager transactionManager) {
        this.shipmentRepository = shipmentRepository;
        this.shipmentEventProducer = shipmentEventProducer;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // Deliberately not @Transactional: a duplicate (unique order_id) must be caught here, outside the
    // transaction that failed, and a failed flush inside an outer one would only surface at commit,
    // after this method, as a retry loop. The transaction is the TransactionTemplate below.
    @KafkaListener(topics = "payment.completed", groupId = "shipping-service")
    public void handlePaymentCompleted(PaymentCompletedEvent event) {
        if (!isAcceptable(event)) {
            // log and drop: throwing would make the container redeliver the same bad record
            long suppressed = rejectedLog.acquire("rejected");
            if (suppressed >= 0) {
                log.warn("Ignoring payment.completed without a valid order, customer or positive amount (orderId={}){}",
                        event == null ? null : event.getOrderId(), LogThrottle.suppressedSuffix(suppressed));
            }
            return;
        }
        log.debug("Received payment.completed for orderId={}", event.getOrderId());

        Shipment shipment;
        try {
            shipment = transactions.execute(status -> createShipment(event));
        } catch (DataIntegrityViolationException e) {
            if (shipmentRepository.findByOrderId(event.getOrderId()).isPresent()) {
                // lost the unique(order_id) race with a concurrent delivery of the same event: the
                // winner created the shipment and queued shipment.created, nothing is left to do here
                alreadyShipped(event);
                return;
            }
            throw e;
        }
        if (shipment == null) {
            alreadyShipped(event);
            return;
        }
        log.info("Shipment created for orderId={}, tracking={}", event.getOrderId(), shipment.getTrackingNumber());
    }

    /**
     * One transaction: the shipment row and its shipment.created outbox row are committed together, so a
     * crash or a Kafka outage cannot leave a shipment nobody hears about (a redelivery would find the
     * shipment and skip it). Returns null when the order already has its shipment.
     */
    private Shipment createShipment(PaymentCompletedEvent event) {
        if (shipmentRepository.findByOrderId(event.getOrderId()).isPresent()) {
            return null;
        }
        Shipment shipment = Shipment.builder()
                .orderId(event.getOrderId())
                .customerId(event.getCustomerId())
                .trackingNumber(TrackingNumbers.generate())
                .originAddress("Warehouse, 123 Storage St")
                .destinationAddress("Customer Address")
                .carrier("DHL")
                .estimatedDelivery(LocalDateTime.now().plusDays(5))
                .build();
        shipmentRepository.save(shipment);

        shipmentEventProducer.publishShipmentCreated(ShipmentCreatedEvent.builder()
                .shipmentId(shipment.getId()).orderId(shipment.getOrderId())
                .customerId(shipment.getCustomerId()).trackingNumber(shipment.getTrackingNumber())
                .carrier(shipment.getCarrier()).estimatedDelivery(shipment.getEstimatedDelivery())
                .build());
        return shipment;
    }

    private void alreadyShipped(PaymentCompletedEvent event) {
        long suppressed = duplicateLog.acquire("duplicate");
        if (suppressed >= 0) {
            log.info("Shipment already exists for orderId={}, duplicate payment.completed ignored{}",
                    event.getOrderId(), LogThrottle.suppressedSuffix(suppressed));
        }
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
