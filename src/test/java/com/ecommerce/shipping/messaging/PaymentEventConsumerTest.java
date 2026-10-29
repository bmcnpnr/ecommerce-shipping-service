package com.ecommerce.shipping.messaging;

import com.ecommerce.shipping.event.PaymentCompletedEvent;
import com.ecommerce.shipping.event.ShipmentCreatedEvent;
import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentEventConsumerTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentEventProducer shipmentEventProducer;
    @InjectMocks PaymentEventConsumer consumer;

    private static PaymentCompletedEvent payment(long orderId) {
        return PaymentCompletedEvent.builder().paymentId(501L).orderId(orderId).customerId("customer-42")
                .amount(new BigDecimal("99.98")).transactionId("TXN-1A2B3C4D").completedAt(LocalDateTime.now()).build();
    }

    @Test
    void newOrder_createsPendingShipment_andPublishesShipmentCreated() {
        when(shipmentRepository.findByOrderId(7L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(inv -> {
            Shipment s = inv.getArgument(0);
            s.setId(31L);
            return s;
        });
        LocalDateTime before = LocalDateTime.now();

        consumer.handlePaymentCompleted(payment(7L));

        ArgumentCaptor<Shipment> saved = ArgumentCaptor.forClass(Shipment.class);
        verify(shipmentRepository).save(saved.capture());
        Shipment s = saved.getValue();
        assertThat(s.getOrderId()).isEqualTo(7L);
        assertThat(s.getCustomerId()).isEqualTo("customer-42");
        assertThat(s.getStatus()).isEqualTo(ShipmentStatus.PENDING);
        assertThat(s.getCarrier()).isEqualTo("DHL");
        assertThat(s.getOriginAddress()).isEqualTo("Warehouse, 123 Storage St");
        assertThat(s.getDestinationAddress()).isEqualTo("Customer Address");
        assertThat(s.getTrackingNumber()).matches("TRACK-[0-9A-F]{8}-[0-9A-F]{8}");
        assertThat(s.getEstimatedDelivery()).isBetween(before.plusDays(5), LocalDateTime.now().plusDays(5));
        assertThat(s.getActualDelivery()).isNull();

        ArgumentCaptor<ShipmentCreatedEvent> published = ArgumentCaptor.forClass(ShipmentCreatedEvent.class);
        verify(shipmentEventProducer).publishShipmentCreated(published.capture());
        ShipmentCreatedEvent e = published.getValue();
        assertThat(e.getShipmentId()).isEqualTo(31L);
        assertThat(e.getOrderId()).isEqualTo(7L);
        assertThat(e.getCustomerId()).isEqualTo("customer-42");
        assertThat(e.getTrackingNumber()).isEqualTo(s.getTrackingNumber());
        assertThat(e.getCarrier()).isEqualTo("DHL");
        assertThat(e.getEstimatedDelivery()).isEqualTo(s.getEstimatedDelivery());
    }

    @Test
    void redeliveredPayment_forOrderWithShipment_isIgnored() {
        when(shipmentRepository.findByOrderId(7L)).thenReturn(Optional.of(Shipment.builder().id(31L).orderId(7L).build()));

        consumer.handlePaymentCompleted(payment(7L));

        verify(shipmentRepository, never()).save(any());
        verifyNoInteractions(shipmentEventProducer);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-5.00", "0.00"})
    void zeroOrNegativeAmount_isIgnored_withoutShipmentOrEvent(String amount) {
        PaymentCompletedEvent event = payment(7L);
        event.setAmount(new BigDecimal(amount));

        consumer.handlePaymentCompleted(event);

        verifyNoInteractions(shipmentRepository, shipmentEventProducer);
    }

    @Test
    void missingAmountOrIds_areIgnored() {
        PaymentCompletedEvent noAmount = payment(7L);
        noAmount.setAmount(null);
        PaymentCompletedEvent noOrder = payment(7L);
        noOrder.setOrderId(null);
        PaymentCompletedEvent noCustomer = payment(7L);
        noCustomer.setCustomerId(" ");

        consumer.handlePaymentCompleted(noAmount);
        consumer.handlePaymentCompleted(noOrder);
        consumer.handlePaymentCompleted(noCustomer);
        consumer.handlePaymentCompleted(null);

        verifyNoInteractions(shipmentRepository, shipmentEventProducer);
    }

    @Test
    void concurrentDuplicate_lostTheUniqueRace_isIdempotent() {
        when(shipmentRepository.findByOrderId(7L)).thenReturn(Optional.empty(), Optional.of(Shipment.builder().id(31L).orderId(7L).build()));
        when(shipmentRepository.save(any(Shipment.class))).thenThrow(new DataIntegrityViolationException("duplicate key"));

        consumer.handlePaymentCompleted(payment(7L));

        verifyNoInteractions(shipmentEventProducer);
    }
}
