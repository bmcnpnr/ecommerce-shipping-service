package com.ecommerce.shipping.controller;

import com.ecommerce.shipping.dto.*;
import com.ecommerce.shipping.security.RequestIdentity;
import com.ecommerce.shipping.service.ShipmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/**
 * Authorization (see security-fix-spec.md): create and status update are admin; reading by order or
 * history is owner-or-admin (owner = the shipment's customerId vs X-Username); tracking is public.
 */
@RestController
@RequestMapping("/api/v1/shipments")
@RequiredArgsConstructor
@Tag(name = "Shipments", description = "Shipment tracking")
public class ShipmentController {

    private static final String USER = "X-Username";
    private static final String ROLE = "X-User-Role";

    private final ShipmentService shipmentService;

    @PostMapping
    @Operation(summary = "Create a shipment (admin)")
    public ResponseEntity<ShipmentDTO> createShipment(@RequestHeader(value = USER, required = false) String username,
                                                      @RequestHeader(value = ROLE, required = false) String role,
                                                      @Valid @RequestBody CreateShipmentRequest request) {
        RequestIdentity.requireAdmin(username, role);
        return ResponseEntity.status(HttpStatus.CREATED).body(shipmentService.createShipment(request));
    }

    @GetMapping("/order/{orderId}")
    @Operation(summary = "Shipment of an order (owner or admin)")
    public ResponseEntity<ShipmentDTO> getByOrderId(@RequestHeader(value = USER, required = false) String username,
                                                    @RequestHeader(value = ROLE, required = false) String role,
                                                    @PathVariable Long orderId) {
        RequestIdentity.requireAuthenticated(username);
        ShipmentDTO shipment = shipmentService.getShipmentByOrderId(orderId);
        RequestIdentity.requireOwnerOrAdmin(username, role, shipment.getCustomerId());
        return ResponseEntity.ok(shipment);
    }

    @GetMapping("/tracking/{trackingNumber}")
    @Operation(summary = "Track shipment by tracking number (public)")
    public ResponseEntity<PublicTrackingDTO> trackShipment(@PathVariable String trackingNumber) {
        return ResponseEntity.ok(shipmentService.getShipmentByTrackingNumber(trackingNumber));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Update shipment status (admin)")
    public ResponseEntity<ShipmentDTO> updateStatus(@RequestHeader(value = USER, required = false) String username,
                                                    @RequestHeader(value = ROLE, required = false) String role,
                                                    @PathVariable Long id,
                                                    @Valid @RequestBody UpdateShipmentStatusRequest request) {
        RequestIdentity.requireAdmin(username, role);
        return ResponseEntity.ok(shipmentService.updateShipmentStatus(id, request.getStatus(), request.getLocation(), request.getDescription()));
    }

    @GetMapping("/{id}/history")
    @Operation(summary = "Shipment history (owner or admin)")
    public ResponseEntity<List<ShipmentEventDTO>> getHistory(@RequestHeader(value = USER, required = false) String username,
                                                             @RequestHeader(value = ROLE, required = false) String role,
                                                             @PathVariable Long id) {
        RequestIdentity.requireAuthenticated(username);
        RequestIdentity.requireOwnerOrAdmin(username, role, shipmentService.getShipmentOwner(id));
        return ResponseEntity.ok(shipmentService.getShipmentHistory(id));
    }
}
