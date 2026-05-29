package com.certguard.renewal.ca;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Stub CA provider used when no CA integration is configured.
 * Throws CaNotConfiguredException so the renewal fails gracefully with a clear message.
 */
@Slf4j
@Component
public class NoopCaProvider implements CaProvider {

    @Override
    public String type() { return "NOOP"; }

    @Override
    public CaIssuedPackage requestCertificate(CaRenewalRequest request) {
        log.warn("NOOP CA provider invoked — no CA integration configured for org: {}", request.orgId());
        throw new CaNotConfiguredException(
                "No CA provider is configured. Please configure a CA provider in the renewal settings.");
    }

    @Override
    public CaIssuedPackage pollOrder(String externalRef) {
        throw new CaNotConfiguredException("NOOP CA provider does not support polling.");
    }
}
