package com.ecommerce.shipping.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentDTO {
    private Long id;
    private Long orderId;
    private String customerId;
    private String trackingNumber;
    private String status;
    private String carrier;
    private String originAddress;
    private String destinationAddress;
    private LocalDateTime estimatedDelivery;
    private LocalDateTime actualDelivery;
    private LocalDateTime createdAt;
}
