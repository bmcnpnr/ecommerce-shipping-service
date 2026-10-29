package com.ecommerce.shipping.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

@Data @NoArgsConstructor @AllArgsConstructor
public class UpdateShipmentStatusRequest {
    private static final String NO_NUL = "[^\\u0000]*";

    @NotBlank @Size(max = 30) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String status;
    @Size(max = 255) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String location;
    @Size(max = 500) @Pattern(regexp = NO_NUL, message = "must not contain NUL characters") private String description;
}
