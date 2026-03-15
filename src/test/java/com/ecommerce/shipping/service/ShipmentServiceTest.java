package com.ecommerce.shipping.service;

import com.ecommerce.shipping.dto.CreateShipmentRequest;
import com.ecommerce.shipping.dto.ShipmentDTO;
import com.ecommerce.shipping.exception.*;
import com.ecommerce.shipping.messaging.ShipmentEventProducer;
import com.ecommerce.shipping.model.*;
import com.ecommerce.shipping.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock ShipmentRepository shipmentRepository;
    @Mock ShipmentEventRepository shipmentEventRepository;
    @Mock ShipmentEventProducer eventProducer;
    @InjectMocks ShipmentService shipmentService;

    private Shipment testShipment;

    @BeforeEach
    void setUp() {
        testShipment = Shipment.builder().id(1L).orderId(10L).customerId("user1")
                .trackingNumber("TRACK-ABC").status(ShipmentStatus.PENDING)
                .originAddress("A").destinationAddress("B").events(new ArrayList<>()).build();
    }

    @Test
    void createShipment_success() {
        CreateShipmentRequest req = CreateShipmentRequest.builder()
                .orderId(10L).customerId("user1").originAddress("A").destinationAddress("B").build();
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(shipmentRepository.save(any())).thenAnswer(inv -> { Shipment s = inv.getArgument(0); s.setId(1L); return s; });

        ShipmentDTO result = shipmentService.createShipment(req);
        assertThat(result.getOrderId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void createShipment_duplicate_throws() {
        when(shipmentRepository.findByOrderId(10L)).thenReturn(Optional.of(testShipment));
        assertThatThrownBy(() -> shipmentService.createShipment(
                CreateShipmentRequest.builder().orderId(10L).customerId("u").originAddress("A").destinationAddress("B").build()))
                .isInstanceOf(InvalidShipmentStateException.class);
    }

    @Test
    void getShipmentByTrackingNumber_notFound_throws() {
        when(shipmentRepository.findByTrackingNumber("UNKNOWN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> shipmentService.getShipmentByTrackingNumber("UNKNOWN"))
                .isInstanceOf(ShipmentNotFoundException.class);
    }

    @Test
    void updateStatus_alreadyDelivered_throws() {
        testShipment.setStatus(ShipmentStatus.DELIVERED);
        when(shipmentRepository.findById(1L)).thenReturn(Optional.of(testShipment));
        assertThatThrownBy(() -> shipmentService.updateShipmentStatus(1L, "SHIPPED", null, null))
                .isInstanceOf(InvalidShipmentStateException.class);
    }
}
