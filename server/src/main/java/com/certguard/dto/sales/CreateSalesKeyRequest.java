package com.certguard.dto.sales;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CreateSalesKeyRequest {

    @NotBlank
    @Size(max = 100)
    private String label;

    private Instant expiresAt;
}
