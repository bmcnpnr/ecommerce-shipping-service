package com.ecommerce.shipping.dto;

import lombok.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ShipmentEventDTO {
    private Long id;
    private Long shipmentId;
    private String eventDescription;
    private String newStatus;
    private String location;
    private LocalDateTime eventTimestamp;
}
