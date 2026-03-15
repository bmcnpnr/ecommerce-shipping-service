package com.ecommerce.shipping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateShipmentRequest {
    @NotNull private Long orderId;
    @NotBlank private String customerId;
    @NotBlank private String originAddress;
    @NotBlank private String destinationAddress;
    private String carrier;
    private String estimatedDelivery;
}
