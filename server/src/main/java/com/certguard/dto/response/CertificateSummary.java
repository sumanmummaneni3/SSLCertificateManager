package com.certguard.dto.response;
import com.certguard.enums.CertStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
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

    /**
     * Scan provenance (RFC 0013 §9). Omitted entirely (not even {@code null}) for legacy
     * records with no recorded provenance — see {@code ScanSourceMapper}.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private ScanSource scanSource;
}
