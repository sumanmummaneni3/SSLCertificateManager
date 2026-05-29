package com.certguard.renewal.ca;

import java.util.List;
import java.util.UUID;

/**
 * SPI for certificate authority integrations.
 * Each implementation handles a specific CA (Let's Encrypt, DigiCert, etc.).
 */
public interface CaProvider {

    String type();

    /**
     * Submits a CSR to the CA and returns issued certificate material.
     * Private key is NEVER involved — only the CSR (public) is sent.
     */
    CaIssuedPackage requestCertificate(CaRenewalRequest request) throws CaProviderException;

    /**
     * Polls the CA for an async order result (used for ACME challenge-based issuance).
     */
    CaIssuedPackage pollOrder(String externalRef) throws CaProviderException;
}
