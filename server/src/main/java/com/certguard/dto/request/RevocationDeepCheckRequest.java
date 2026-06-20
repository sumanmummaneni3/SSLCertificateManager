package com.certguard.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Request body for PATCH .../certificates/{certId}/revocation-deep-check (RFC 0009 §10.2).
 */
@Data
public class RevocationDeepCheckRequest {
    @NotNull
    private Boolean enabled;
}
