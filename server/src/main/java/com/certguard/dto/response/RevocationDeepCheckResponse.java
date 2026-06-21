package com.certguard.dto.response;

import lombok.Builder;
import lombok.Value;

import java.util.UUID;

/**
 * Response for PATCH .../certificates/{certId}/revocation-deep-check (RFC 0009 §10.2).
 */
@Value
@Builder
public class RevocationDeepCheckResponse {
    UUID id;
    boolean revocationDeepCheck;
}
