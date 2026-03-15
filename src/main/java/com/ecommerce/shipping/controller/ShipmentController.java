package com.ecommerce.shipping.controller;

import com.ecommerce.shipping.dto.*;
import com.ecommerce.shipping.service.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@Tag(name = "Shipments", description = "Shipment tracking")
public class ShipmentController {

    private final ShipmentService shipmentService;

    @PostMapping
    public ResponseEntity<ShipmentDTO> createShipment(@Valid @RequestBody CreateShipmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(shipmentService.createShipment(request));
    }

    @GetMapping("/order/{orderId}")
    public ResponseEntity<ShipmentDTO> getByOrderId(@PathVariable Long orderId) {
        return ResponseEntity.ok(shipmentService.getShipmentByOrderId(orderId));
    }

    @GetMapping("/tracking/{trackingNumber}")
    @Operation(summary = "Track shipment by tracking number (public)")
    public ResponseEntity<ShipmentDTO> trackShipment(@PathVariable String trackingNumber) {
        return ResponseEntity.ok(shipmentService.getShipmentByTrackingNumber(trackingNumber));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ShipmentDTO> updateStatus(@PathVariable Long id,
                                                      @Valid @RequestBody UpdateShipmentStatusRequest request) {
        return ResponseEntity.ok(shipmentService.updateShipmentStatus(id, request.getStatus(), request.getLocation(), request.getDescription()));
    }

    @GetMapping("/{id}/history")
    public ResponseEntity<List<ShipmentEventDTO>> getHistory(@PathVariable Long id) {
        return ResponseEntity.ok(shipmentService.getShipmentHistory(id));
    }
}
