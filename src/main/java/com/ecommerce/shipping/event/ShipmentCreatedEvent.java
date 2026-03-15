package com.ecommerce.shipping.event;

import lombok.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentCreatedEvent {
    private Long shipmentId;
    private Long orderId;
    private String customerId;
    private String trackingNumber;
    private String carrier;
    private LocalDateTime estimatedDelivery;
}
