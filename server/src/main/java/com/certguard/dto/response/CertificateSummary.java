package com.certguard.dto.response;
import com.certguard.enums.CertStatus;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.UUID;
@Data @Builder
public class CertificateSummary {
    private UUID id;
    private String commonName;
    private String issuer;
    private Instant expiryDate;
    private long daysRemaining;
    private CertStatus status;
}
