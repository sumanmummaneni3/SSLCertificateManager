package com.certguard.dto.request;
import jakarta.validation.constraints.*;
import lombok.Data;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
@Data
public class AgentScanResultRequest {
    @NotNull private UUID jobId;
    @NotNull private UUID targetId;
    @NotBlank private String scanType;
    @NotBlank private String serialNumber;
    @NotNull private Instant notAfter;
    @NotBlank private String hmacSignature;
    // FULL only
    private String commonName;
    private String issuer;
    private Instant notBefore;
    private String keyAlgorithm;
    private Integer keySize;
    private String signatureAlgorithm;
    private List<String> subjectAltNames;
    private Integer chainDepth;
    private String publicCertB64;
    // DELTA only
    private UUID certificateId;
}
