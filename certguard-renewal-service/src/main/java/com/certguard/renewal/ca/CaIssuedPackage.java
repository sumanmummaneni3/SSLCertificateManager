package com.certguard.renewal.ca;

import java.time.Instant;

public record CaIssuedPackage(
        String externalRef,
        String leafCertPem,
        String chainPem,
        Instant expiresAt,
        String serialNumber,
        String issuerDn
) {
    public String fullPem() {
        return leafCertPem + "\n" + (chainPem != null ? chainPem : "");
    }
}
