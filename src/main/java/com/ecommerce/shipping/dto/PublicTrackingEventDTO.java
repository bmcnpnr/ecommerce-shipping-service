package com.ecommerce.shipping.dto;

import lombok.*;
import java.time.LocalDateTime;

/** One history entry of the public tracking response. */
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PublicTrackingEventDTO {
    private String status;
    private String location;
    private String description;
    private LocalDateTime timestamp;
}
