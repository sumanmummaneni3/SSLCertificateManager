package com.certguard.agent.security;

import com.certguard.agent.model.ScanResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Computes HMAC-SHA256 signature for scan result submissions.
 *
 * Payload format (must match server-side AgentHmacService.compute()):
 *   targetId + ":" + scanType + ":" + serialNumber + ":" + notAfter.toEpochMilli()
 */
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    public static String sign(ScanResult result, String agentKey) throws Exception {
        String payload = result.getTargetId()          + ":"
                + result.getScanType()                 + ":"
                + result.getSerialNumber()             + ":"
                + result.getNotAfter().toEpochMilli();

        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(agentKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }
}
