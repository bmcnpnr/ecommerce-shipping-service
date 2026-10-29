package com.ecommerce.shipping.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

/** Response of the anonymous tracking lookup: deliberately no customer, order id or addresses. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PublicTrackingDTO {
    private String trackingNumber;
    private String status;
    private String carrier;
    private LocalDateTime estimatedDelivery;
    private List<PublicTrackingEventDTO> events;
}
