package com.ecommerce.shipping.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class UpdateShipmentStatusRequest {
    @NotBlank private String status;
    private String location;
    private String description;
}
