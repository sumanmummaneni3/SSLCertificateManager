package com.certguard.agent.security;

import com.certguard.agent.model.ScanResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Computes HMAC-SHA256 signature for scan result submissions.
 *
 * Payload format (must match server-side AgentHmacService.compute()):
 *
 * DELTA / ERROR:
 *   targetId + ":" + scanType + ":" + serialNumber + ":" + notAfterEpochMs
 *
 * FULL:
 *   targetId + ":" + scanType + ":" + serialNumber + ":" + notAfterEpochMs
 *   + ":" + commonName + ":" + issuer + ":" + keyAlgorithm + ":" + keySize
 *   + ":" + signatureAlgorithm + ":" + chainDepth
 *   + ":" + sortedSANs (sorted and joined with ",")
 */
public class HmacSigner {

    private static final String ALGORITHM = "HmacSHA256";

    public static String sign(ScanResult result, String agentKey) throws Exception {
        String payload = buildPayload(result);

        Mac mac = Mac.getInstance(ALGORITHM);
        mac.init(new SecretKeySpec(agentKey.getBytes(StandardCharsets.UTF_8), ALGORITHM));
        byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String buildPayload(ScanResult result) {
        String base = result.getTargetId()    + ":"
                + result.getScanType()        + ":"
                + result.getSerialNumber()    + ":"
                + result.getNotAfter().toEpochMilli();

        if (ScanResult.Type.FULL.name().equals(result.getScanType())) {
            List<String> sans = result.getSubjectAltNames() != null
                    ? result.getSubjectAltNames() : Collections.emptyList();
            List<String> sortedSans = sans.stream().sorted().collect(java.util.stream.Collectors.toList());
            return base
                    + ":" + nullToEmpty(result.getCommonName())
                    + ":" + nullToEmpty(result.getIssuer())
                    + ":" + nullToEmpty(result.getKeyAlgorithm())
                    + ":" + (result.getKeySize() != null ? result.getKeySize() : "")
                    + ":" + nullToEmpty(result.getSignatureAlgorithm())
                    + ":" + (result.getChainDepth() != null ? result.getChainDepth() : "")
                    + ":" + String.join(",", sortedSans);
        }

        return base;
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
