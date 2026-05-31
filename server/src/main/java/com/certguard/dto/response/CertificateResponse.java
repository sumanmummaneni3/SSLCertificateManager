package com.certguard.dto.response;
import com.certguard.enums.CertStatus;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
@Data @Builder
public class CertificateResponse {
    private UUID id;
    private UUID targetId;
    private String host;
    private int port;
    private String commonName;
    private String issuer;
    private String serialNumber;
    private Instant notBefore;
    private Instant expiryDate;
    private long daysRemaining;
    private CertStatus status;
    private String keyAlgorithm;
    private Integer keySize;
    private String signatureAlgorithm;
    private List<String> subjectAltNames;
    private Integer chainDepth;
    private Instant scannedAt;
    private UUID scannedByAgentId;
    /** Populated on the single-certificate detail endpoint so the UI can tell whether
     *  the target is agent-managed (target.agentId) and offer renewal. Null on list views. */
    private TargetResponse target;
}
