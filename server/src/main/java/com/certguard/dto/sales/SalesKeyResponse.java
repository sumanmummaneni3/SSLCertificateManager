package com.certguard.dto.sales;

import com.certguard.enums.SalesKeyStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SalesKeyResponse {

    private UUID id;
    private String label;
    private SalesKeyStatus status;
    private Instant createdAt;
    private Instant lastUsedAt;
    private Instant expiresAt;

    /**
     * Only populated on key creation. Null on subsequent list/get calls.
     * The caller must store this securely — it cannot be recovered afterwards.
     */
    private String plainKey;
}
