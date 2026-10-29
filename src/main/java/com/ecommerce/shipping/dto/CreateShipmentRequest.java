package com.ecommerce.shipping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class CreateShipmentRequest {
    private static final String NO_NUL = "[^\\u0000]*";

    @NotNull private Long orderId;
    @NotBlank @Size(max = 255) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String customerId;
    @NotBlank @Size(max = 1000) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String originAddress;
    @NotBlank @Size(max = 1000) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String destinationAddress;
    @Size(max = 50) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String carrier;
    @Size(max = 64) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String estimatedDelivery;
}
