package com.certguard.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

@Slf4j
@Service
public class AgentHmacService {

    private static final String ALGORITHM = "HmacSHA256";

    public boolean verify(String agentKey, UUID targetId, String scanType,
                          String serialNumber, Instant notAfter, String claimedHmac) {
        try {
            String expected = compute(agentKey, targetId, scanType, serialNumber, notAfter);
            return constantTimeEquals(expected, claimedHmac);
        } catch (Exception e) {
            log.error("HMAC verification error: {}", e.getMessage());
            return false;
        }
    }

    public String compute(String agentKey, UUID targetId, String scanType,
                          String serialNumber, Instant notAfter) throws Exception {
        String payload = targetId.toString() + ":" + scanType + ":"
                + serialNumber + ":" + notAfter.toEpochMilli();
        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(agentKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        return Base64.getEncoder().encodeToString(
                mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
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
