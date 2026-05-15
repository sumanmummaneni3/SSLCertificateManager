package com.certguard.dto.sales;

import jakarta.validation.constraints.Min;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UpdateQuotaRequest {

    @Min(0)
    private int maxCertificateQuota;

    private String reason;
}
