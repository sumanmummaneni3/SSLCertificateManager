package com.certguard.security;

import com.certguard.dto.request.AgentScanResultRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class AgentHmacService {

    private static final String ALGORITHM = "HmacSHA256";

    /**
     * Verifies the HMAC signature on a scan result request.
     *
     * For FULL scan results the canonical string is widened to cover
     * commonName, issuer, keyAlgorithm, keySize, signatureAlgorithm,
     * chainDepth, and the sorted SAN list, preventing field-level tampering.
     * For DELTA results only the original 4-field string is used.
     */
    public boolean verify(String agentKey, AgentScanResultRequest request, String claimedHmac) {
        try {
            String expected = compute(agentKey, request);
            return constantTimeEquals(expected, claimedHmac);
        } catch (Exception e) {
            log.error("HMAC verification error: {}", e.getMessage());
            return false;
        }
    }

    public String compute(String agentKey, AgentScanResultRequest request) throws Exception {
        String payload = buildPayload(request);
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(agentKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private String buildPayload(AgentScanResultRequest req) {
        String base = req.getTargetId().toString() + ":"
                + req.getScanType() + ":"
                + req.getSerialNumber() + ":"
                + req.getNotAfter().toEpochMilli();

        if ("FULL".equals(req.getScanType())) {
            List<String> sans = req.getSubjectAltNames() != null
                    ? req.getSubjectAltNames() : Collections.emptyList();
            List<String> sortedSans = sans.stream().sorted().collect(java.util.stream.Collectors.toList());
            return base
                    + ":" + nullToEmpty(req.getCommonName())
                    + ":" + nullToEmpty(req.getIssuer())
                    + ":" + nullToEmpty(req.getKeyAlgorithm())
                    + ":" + (req.getKeySize() != null ? req.getKeySize() : "")
                    + ":" + nullToEmpty(req.getSignatureAlgorithm())
                    + ":" + (req.getChainDepth() != null ? req.getChainDepth() : "")
                    + ":" + String.join(",", sortedSans);
        }

        return base;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        if (ab.length != bb.length) return false;
        int result = 0;
        for (int i = 0; i < ab.length; i++) result |= ab[i] ^ bb[i];
        return result == 0;
    }
}
