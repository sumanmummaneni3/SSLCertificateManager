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
    // serialNumber and notAfter are required for FULL/DELTA but optional for ERROR.
    // @NotBlank/@NotNull removed to allow ERROR submissions without cert data.
    private String serialNumber;
    private Instant notAfter;
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

    /**
     * FULL only -- full chain (base64 DER), leaf at index 0, then intermediates.
     * RFC 0009 §3.4: optional for backward compat with older agents that don't send it.
     * When absent, server records INCOMPLETE_CHAIN and attempts AIA completion.
     */
    private List<String> chainB64;

    /**
     * FULL only -- raw OCSP staple bytes, base64-encoded (RFC 0013 §4).
     * Present when the server sent a status_request extension and included a staple.
     * Optional: null if the server did not staple an OCSP response.
     * Allows RevocationCheckService to consume the staple without a repeat OCSP call.
     */
    private String ocspStapleB64;

    /**
     * ERROR only -- human-readable error from the scanning agent (RFC 0013 §5).
     * Present when scanType = "ERROR"; null for FULL and DELTA results.
     */
    private String errorMessage;
}
