package com.ecommerce.shipping.event;

import lombok.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentUpdatedEvent {
    private Long shipmentId;
    private Long orderId;
    private String trackingNumber;
    private String newStatus;
    private String location;
    private LocalDateTime updatedAt;
}
