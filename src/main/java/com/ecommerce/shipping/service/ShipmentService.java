package com.ecommerce.shipping.service;

import com.ecommerce.shipping.dto.*;
import com.ecommerce.shipping.event.*;
import com.ecommerce.shipping.exception.*;
import com.ecommerce.shipping.messaging.ShipmentEventProducer;
import com.ecommerce.shipping.model.*;
import com.ecommerce.shipping.repository.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class ShipmentService {

    private static final Logger log = LoggerFactory.getLogger(ShipmentService.class);

    private final ShipmentRepository shipmentRepository;
    private final ShipmentEventRepository shipmentEventRepository;
    private final ShipmentEventProducer eventProducer;

    @Transactional
    public ShipmentDTO createShipment(CreateShipmentRequest request) {
        shipmentRepository.findByOrderId(request.getOrderId()).ifPresent(s -> {
            throw new InvalidShipmentStateException("Shipment already exists for order: " + request.getOrderId());
        });

        String trackingNumber = "TRACK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();

        Shipment shipment = Shipment.builder()
                .orderId(request.getOrderId())
                .customerId(request.getCustomerId())
                .trackingNumber(trackingNumber)
                .originAddress(request.getOriginAddress())
                .destinationAddress(request.getDestinationAddress())
                .carrier(request.getCarrier() != null ? request.getCarrier() : "DHL")
                .estimatedDelivery(LocalDateTime.now().plusDays(5))
                .build();

        Shipment saved = shipmentRepository.save(shipment);
        log.info("Shipment created: tracking={}, orderId={}", trackingNumber, request.getOrderId());
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public ShipmentDTO getShipmentByOrderId(Long orderId) {
        return toDTO(shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new ShipmentNotFoundException("Shipment not found for order: " + orderId)));
    }

    @Transactional(readOnly = true)
    public ShipmentDTO getShipmentByTrackingNumber(String trackingNumber) {
        return toDTO(shipmentRepository.findByTrackingNumber(trackingNumber)
                .orElseThrow(() -> new ShipmentNotFoundException("Shipment not found: " + trackingNumber)));
    }

    @Transactional
    public ShipmentDTO updateShipmentStatus(Long shipmentId, String statusStr, String location, String description) {
        Shipment shipment = shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ShipmentNotFoundException("Shipment not found: " + shipmentId));

        ShipmentStatus newStatus = ShipmentStatus.valueOf(statusStr);

        if (shipment.getStatus() == ShipmentStatus.DELIVERED || shipment.getStatus() == ShipmentStatus.RETURNED) {
            throw new InvalidShipmentStateException("Cannot update status of a " + shipment.getStatus() + " shipment");
        }

        shipment.setStatus(newStatus);
        if (newStatus == ShipmentStatus.DELIVERED) {
            shipment.setActualDelivery(LocalDateTime.now());
        }
        shipment.setUpdatedAt(LocalDateTime.now());

        ShipmentEvent event = ShipmentEvent.builder()
                .shipment(shipment)
                .eventDescription(description != null ? description : "Status updated to " + newStatus)
                .newStatus(newStatus)
                .location(location)
                .eventTimestamp(LocalDateTime.now())
                .build();
        shipment.getEvents().add(event);

        Shipment saved = shipmentRepository.save(shipment);

        eventProducer.publishShipmentUpdated(ShipmentUpdatedEvent.builder()
                .shipmentId(saved.getId()).orderId(saved.getOrderId())
                .trackingNumber(saved.getTrackingNumber()).newStatus(newStatus.name())
                .location(location).updatedAt(LocalDateTime.now()).build());

        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public List<ShipmentEventDTO> getShipmentHistory(Long shipmentId) {
        shipmentRepository.findById(shipmentId)
                .orElseThrow(() -> new ShipmentNotFoundException("Shipment not found: " + shipmentId));
        return shipmentEventRepository.findByShipmentIdOrderByEventTimestampAsc(shipmentId).stream()
                .map(e -> ShipmentEventDTO.builder()
                        .id(e.getId()).shipmentId(shipmentId)
                        .eventDescription(e.getEventDescription())
                        .newStatus(e.getNewStatus() != null ? e.getNewStatus().name() : null)
                        .location(e.getLocation()).eventTimestamp(e.getEventTimestamp()).build())
                .collect(Collectors.toList());
    }

    private ShipmentDTO toDTO(Shipment s) {
        return ShipmentDTO.builder()
                .id(s.getId()).orderId(s.getOrderId()).customerId(s.getCustomerId())
                .trackingNumber(s.getTrackingNumber()).status(s.getStatus().name())
                .carrier(s.getCarrier()).originAddress(s.getOriginAddress())
                .destinationAddress(s.getDestinationAddress())
                .estimatedDelivery(s.getEstimatedDelivery()).actualDelivery(s.getActualDelivery())
                .createdAt(s.getCreatedAt()).build();
    }
}
